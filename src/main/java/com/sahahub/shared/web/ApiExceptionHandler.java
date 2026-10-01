package com.sahahub.shared.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;

/**
 * JSON uç noktaları (/api/**) için RFC 9457 ProblemDetail yanıtları. @RestController aynı zamanda
 * @Controller sayıldığı için HTML işleyicisinden önce çalışması gerekir (@Order).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(annotations = RestController.class)
public class ApiExceptionHandler {

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail notFound(NotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(BusinessRuleException.class)
	ProblemDetail rule(BusinessRuleException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
	}

}
