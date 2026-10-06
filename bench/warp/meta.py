#!/usr/bin/env python3
"""Writes meta.json for one benchmark run: git, SectoriaDB configuration snapshot, warp version/args, hardware.

Usage: meta.py OUT_JSON KEY=VALUE...   (run.sh passes the run parameters as KEY=VALUE pairs)
Everything is best effort: a missing tool leaves a null instead of failing the run. Secrets are masked.
"""
import json, os, platform, re, shutil, subprocess, sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def sh(cmd, default=None, timeout=20):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        out = r.stdout.strip()
        return out if r.returncode == 0 and out else default
    except Exception:
        return default


def mask_env(line):
    k, _, v = line.partition("=")
    if re.search(r"SECRET|CREDENTIALS|PASSWORD|TOKEN|KEY$", k) and not k.endswith("ACCESS_KEY_ID"):
        return k + "=***"
    return line


def read(path, limit=20000):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read(limit)
    except OSError:
        return None


def hardware():
    cpu = sh("lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -1") or \
        sh("grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ //'")
    mem_kb = sh("awk '/MemTotal/ {print $2}' /proc/meminfo")
    docker_root = sh("docker info --format '{{.DockerRootDir}}' 2>/dev/null") or "/var/lib/docker"
    data_mount = sh(f"df -T {docker_root} 2>/dev/null | tail -1")
    dev = sh(f"df {docker_root} 2>/dev/null | tail -1 | awk '{{print $1}}'")
    base = os.path.basename(dev) if dev else ""
    base = re.sub(r"p?\d+$", "", base) if base else ""
    rota = read(f"/sys/block/{base}/queue/rotational") if base else None
    return {
        "nproc": int(sh("nproc", "0")),
        "cpu_model": cpu,
        "ram_mib": int(mem_kb) // 1024 if mem_kb else None,
        "kernel": platform.release(),
        "os": sh("sed -n 's/^PRETTY_NAME=//p' /etc/os-release | tr -d '\"'"),
        "docker_data_root": docker_root,
        "docker_data_fs": data_mount,
        "disk_device": dev,
        "disk_rotational": None if rota is None else rota.strip() == "1",
        "disk_type": ("virtual disk (virtio): the rotational flag is not reliable, ask the provider" if base.startswith("vd")
                      else None if rota is None else ("HDD" if rota.strip() == "1" else "SSD/NVMe")),
        "lsblk": sh("lsblk -d -o NAME,ROTA,SIZE,MODEL,TRAN 2>/dev/null"),
        "virtualization": sh("systemd-detect-virt 2>/dev/null"),
    }


def sectoriadb(container):
    info = {"container": container}
    insp = sh(f"docker inspect {container} 2>/dev/null")
    if insp:
        try:
            d = json.loads(insp)[0]
            env = d["Config"].get("Env", [])
            info["image"] = d["Config"].get("Image")
            info["image_id"] = d.get("Image")
            info["env_overrides"] = [mask_env(e) for e in env
                                     if re.match(r"(SECTORIADB_|JAVA_OPTS|SERVER_|MANAGEMENT_|SPRING_)", e)]
            info["memory_limit_bytes"] = d["HostConfig"].get("Memory")
            info["mounts"] = [{"src": m.get("Name") or m.get("Source"), "dst": m["Destination"]} for m in d.get("Mounts", [])]
        except Exception as e:  # noqa
            info["inspect_error"] = str(e)
        jv = sh(f"docker exec {container} java -version 2>&1 | head -1")
        info["jvm"] = jv
    else:
        info["note"] = "container not found; configuration below is the repository default, not necessarily what ran"
    info["application_properties"] = read(os.path.join(REPO, "sectoriadb-server/src/main/resources/application.properties"))
    return info


def main():
    out = sys.argv[1]
    kv = dict(a.split("=", 1) for a in sys.argv[2:])
    git = {
        "commit": sh(f"git -C {REPO} rev-parse HEAD", "nogit"),
        "short": sh(f"git -C {REPO} rev-parse --short HEAD", "nogit"),
        "branch": sh(f"git -C {REPO} rev-parse --abbrev-ref HEAD"),
        "dirty": kv.pop("git_dirty", "false") == "true",
        "subject": sh(f"git -C {REPO} log -1 --format=%s"),
    }
    meta = {
        "schema": 1,
        "started_utc": kv.pop("started_utc", None),
        "finished_utc": kv.pop("finished_utc", None),
        "git": git,
        "run": {k: kv[k] for k in list(kv) if k not in ("container", "warp_version", "warp_args", "warp_runner")},
        "warp": {"version": kv.get("warp_version"), "runner": kv.get("warp_runner"),
                 "args": kv.get("warp_args", "").split("\x1f") if kv.get("warp_args") else []},
        "sectoriadb": sectoriadb(kv.get("container", "sectoriadb-bench")),
        "hardware": hardware(),
        "bench_host_java": sh("java -version 2>&1 | grep -v JAVA_TOOL_OPTIONS | head -1"),
    }
    with open(out, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    main()
