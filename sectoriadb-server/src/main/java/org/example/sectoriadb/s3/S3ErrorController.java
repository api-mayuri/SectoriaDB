package org.example.sectoriadb.s3;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.observability.ObservabilityAttributes;
import org.example.sectoriadb.s3.xml.S3Error;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Replaces Spring Boot's JSON error page. Errors raised before a controller is chosen (405 for a method nobody
 * maps, 415, 406, unknown paths) are turned into container error dispatches; they must come out as S3 XML errors,
 * not as {@code {"timestamp":...,"status":405,...}}.
 *
 * The path is not {@code /error}: that is a valid bucket name. It is not routable from outside either, because the
 * container only dispatches to it internally and {@code .} cannot start a bucket name.
 */
@Controller
public class S3ErrorController implements ErrorController {

    @RequestMapping(value = "${server.error.path:/.s3-error}",
            produces = {MediaType.APPLICATION_XML_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<S3Error> error(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int value = attr instanceof Integer i ? i : 404;
        HttpStatus status = HttpStatus.resolve(value);
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String code = S3MvcErrors.codeFor(status);
        ObservabilityAttributes.noteErrorCode(request, code);
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error(code, S3MvcErrors.messageFor(status), uri instanceof String s ? s : null, S3Support.requestId()));
    }
}
