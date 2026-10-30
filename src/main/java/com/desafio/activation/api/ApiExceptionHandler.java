package com.desafio.activation.api;

import com.desafio.activation.application.DuplicateRequestException;
import com.desafio.activation.application.RequestNotFoundException;
import com.desafio.activation.observability.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Todos os erros saem como Problem Details (RFC 9457) com o traceId da requisição. Mensagens
 * internas e stack traces só vão para o log.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String PROBLEM_BASE_URI = "https://api.exemplo.com/problems/";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-error", "Dados inválidos",
                "Um ou mais campos não passaram na validação.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler(RequestNotFoundException.class)
    ProblemDetail handleNotFound(RequestNotFoundException ex) {
        return withTraceId(problem(HttpStatus.NOT_FOUND, "not-found", "Solicitação não encontrada", ex.getMessage()));
    }

    @ExceptionHandler(DuplicateRequestException.class)
    ProblemDetail handleDuplicate(DuplicateRequestException ex) {
        return withTraceId(problem(HttpStatus.CONFLICT, "duplicate-request", "Solicitação duplicada", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Erro inesperado", ex);
        return withTraceId(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Erro inesperado",
                "Ocorreu um erro inesperado. Informe o traceId ao suporte."));
    }

    /** Ponto comum das exceções do Spring MVC (corpo ilegível, método não suportado etc.). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            withTraceId(problem);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE_URI + type));
        problem.setTitle(title);
        return problem;
    }

    private static ProblemDetail withTraceId(ProblemDetail problem) {
        String traceId = CorrelationId.current();
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }
}
