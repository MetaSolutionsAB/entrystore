package org.entrystore.rest.springboot.configuration;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.context.request.NativeWebRequest;

import java.util.List;
import java.util.regex.Pattern;

/**
 * This class overrides the default response media type to "application/rdf+xml", when no Accept header is defined
 * Applies only for the entry endpoint. Other endpoints default reply format is set to JSON by MvcConfiguration.
 * The value produced by this Strategy is used to match with the correct endpoint in the controller.
 */
@RequiredArgsConstructor
public class EntryEndpointContentNegotiationStrategy implements ContentNegotiationStrategy {

	// Regex to match URLs like /abc123/entry/xyz456
	private static final Pattern ENTRY_URL_PATTERN = Pattern.compile("^/[^/]+/entry/[^/]+$");

	private final ContentNegotiationStrategy springBootDefaultStrategy;

	@Override
	public @NonNull List<MediaType> resolveMediaTypes(NativeWebRequest webRequest) throws HttpMediaTypeNotAcceptableException {
		HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
		if (servletRequest != null && HttpMethod.GET.name().equals(servletRequest.getMethod()) && ENTRY_URL_PATTERN.matcher(servletRequest.getRequestURI()).matches()) {
			String accept = servletRequest.getHeader("Accept");
			if (StringUtils.isEmpty(accept) || MediaType.ALL_VALUE.equals(accept)) {
				return List.of(MediaType.valueOf("application/rdf+xml"));
			}
		}

		// fallback to default strategy
		return springBootDefaultStrategy.resolveMediaTypes(webRequest);
	}
}
