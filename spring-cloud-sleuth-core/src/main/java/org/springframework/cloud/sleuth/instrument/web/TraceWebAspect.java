/*
 * Copyright 2013-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.web;

import org.apache.commons.logging.Log;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.cloud.sleuth.ErrorParser;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.SpanNamer;
import org.springframework.cloud.sleuth.TraceKeys;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.instrument.async.SpanContinuingTraceCallable;
import org.springframework.web.context.request.async.WebAsyncTask;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;

/**
 * Aspect that adds tracing to
 * <p/>
 * <ul>
 * <li>{@code RestController} annotated classes
 * with public {@link Callable} methods</li>
 * <li>{@link org.springframework.stereotype.Controller} annotated classes with public
 * {@link Callable} methods</li>
 * <li>{@link org.springframework.stereotype.Controller} or
 * {@code RestController} annotated classes with
 * public {@link WebAsyncTask} methods</li>
 * </ul>
 * <p/>
 * For controllers an around aspect is created that wraps the {@link Callable#call()}
 * method execution in {@link org.springframework.cloud.sleuth.TraceCallable}
 * <p/>
 *
 * This aspect will continue a span created by the TraceFilter. It will not create
 * a new span - since the one in TraceFilter will wait until processing has been
 * finished
 *
 * @author Tomasz Nurkewicz, 4financeIT
 * @author Michal Chmielarz, 4financeIT
 * @author Marcin Grzejszczak
 * @author Spencer Gibb
 * @since 1.0.0
 *
 * @see org.springframework.stereotype.Controller
 * @see org.springframework.web.client.RestOperations
 * @see org.springframework.cloud.sleuth.TraceCallable
 * @see org.springframework.cloud.sleuth.Tracer
 * @see org.springframework.cloud.sleuth.instrument.web.TraceFilter
 */
@SuppressWarnings("ArgNamesWarningsInspection")
@Aspect
public class TraceWebAspect {

	private static final Log log = org.apache.commons.logging.LogFactory
			.getLog(TraceWebAspect.class);

	private final Tracer tracer;
	private final SpanNamer spanNamer;
	private final TraceKeys traceKeys;
	private final ErrorParser errorParser;

	public TraceWebAspect(Tracer tracer, SpanNamer spanNamer, TraceKeys traceKeys,
			ErrorParser errorParser) {
		this.tracer = tracer;
		this.spanNamer = spanNamer;
		this.traceKeys = traceKeys;
		this.errorParser = errorParser;
	}

	@Pointcut("@within(org.springframework.web.bind.annotation.RestController)")
	private void anyRestControllerAnnotated() { }// NOSONAR

	@Pointcut("@within(org.springframework.stereotype.Controller)")
	private void anyControllerAnnotated() { } // NOSONAR

	@Pointcut("execution(public java.util.concurrent.Callable *(..))")
	private void anyPublicMethodReturningCallable() { } // NOSONAR

	@Pointcut("(anyRestControllerAnnotated() || anyControllerAnnotated()) && anyPublicMethodReturningCallable()")
	private void anyControllerOrRestControllerWithPublicAsyncMethod() { } // NOSONAR

	@Pointcut("execution(public org.springframework.web.context.request.async.WebAsyncTask *(..))")
	private void anyPublicMethodReturningWebAsyncTask() { } // NOSONAR

	@Pointcut("execution(public * org.springframework.web.servlet.HandlerExceptionResolver.resolveException(..)) && args(request, response, handler, ex)")
	private void anyHandlerExceptionResolver(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) { } // NOSONAR

	@Pointcut("(anyRestControllerAnnotated() || anyControllerAnnotated()) && anyPublicMethodReturningWebAsyncTask()")
	private void anyControllerOrRestControllerWithPublicWebAsyncTaskMethod() { } // NOSONAR

	@Around("anyControllerOrRestControllerWithPublicAsyncMethod()")
	@SuppressWarnings("unchecked")
	public Object wrapWithCorrelationId(ProceedingJoinPoint pjp) throws Throwable {
		Callable<Object> callable = (Callable<Object>) pjp.proceed();
		if (this.tracer.isTracing()) {
			if (log.isDebugEnabled()) {
				log.debug("Wrapping callable with span [" + this.tracer.getCurrentSpan() + "]");
			}
			return new SpanContinuingTraceCallable<>(this.tracer, this.traceKeys, this.spanNamer, callable);
		}
		else {
			return callable;
		}
	}

	@Around("anyControllerOrRestControllerWithPublicWebAsyncTaskMethod()")
	public Object wrapWebAsyncTaskWithCorrelationId(ProceedingJoinPoint pjp) throws Throwable {
		final WebAsyncTask<?> webAsyncTask = (WebAsyncTask<?>) pjp.proceed();
		if (this.tracer.isTracing()) {
			try {
				if (log.isDebugEnabled()) {
					log.debug("Wrapping callable with span [" + this.tracer.getCurrentSpan()
							+ "]");
				}
				Field callableField = WebAsyncTask.class.getDeclaredField("callable");
				callableField.setAccessible(true);
				callableField.set(webAsyncTask, new SpanContinuingTraceCallable<>(this.tracer,
						this.traceKeys, this.spanNamer, webAsyncTask.getCallable()));
			} catch (NoSuchFieldException ex) {
				log.warn("Cannot wrap webAsyncTask's callable with TraceCallable", ex);
			}
		}
		return webAsyncTask;
	}

	@Around("anyHandlerExceptionResolver(request, response, handler, ex)")
	public Object markRequestForSpanClosing(ProceedingJoinPoint pjp,
			HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Throwable {
		Span currentSpan = this.tracer.getCurrentSpan();
		try {
			if (currentSpan != null && !currentSpan.tags().containsKey(Span.SPAN_ERROR_TAG_NAME)) {
				this.errorParser.parseErrorTags(currentSpan, ex);
			}
			return pjp.proceed();
		} finally {
			if (log.isDebugEnabled()) {
				log.debug("Marking span " + currentSpan + " for closure by Trace Filter");
			}
			request.setAttribute(TraceFilter.TRACE_CLOSE_SPAN_REQUEST_ATTR, true);
		}
	}

}
