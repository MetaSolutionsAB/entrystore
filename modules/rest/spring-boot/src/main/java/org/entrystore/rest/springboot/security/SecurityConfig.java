/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.springboot.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.springboot.configuration.CasCustomConfiguration;
import org.entrystore.rest.springboot.configuration.CorsConfig;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.model.auth.UserAuthRole;
import org.entrystore.rest.springboot.service.auth.SamlAuthStateCache;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.cas.authentication.CasAuthenticationProvider;
import org.springframework.security.cas.web.CasAuthenticationFilter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.DefaultRelyingPartyRegistrationResolver;
import org.springframework.security.saml2.provider.service.web.authentication.OpenSaml4AuthenticationRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.Saml2AuthenticationRequestResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Optional;

@Slf4j
@EnableMethodSecurity
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	private final CheckUsernamePasswordFilter checkUsernamePasswordFilter;
	private final IgnoreAuthFilter ignoreAuthFilter;
	private final SetUserURIAfterAuthenticationFilter setUserURIAfterAuthenticationFilter;
	private final ReloadUserPropertiesFilter reloadUserPropertiesFilter;
	private final HandlerExceptionResolver handlerExceptionResolver;
	private final FormLoginAuthenticationFailureHandler formLoginAuthenticationFailureHandler;
	private final FormLoginAuthenticationSuccessHandler formLoginAuthenticationSuccessHandler;

	private final CorsConfig corsConfig;

	// SAML-auth related beans
	private final SamlCustomConfiguration samlConfiguration;
	private final SamlLoginSuccessHandler samlLoginSuccessHandler;
	private final Optional<RelyingPartyRegistrationRepository> repo; // optional as it will be injected only when Spring's SAML properties are configured
	private final SamlAuthStateCache samlAuthStateCache;

	// CAS-auth related beans (optional — only present when entrystore.auth.cas.enabled=true)
	private final CasCustomConfiguration casConfiguration;
	private final Optional<CasAuthenticationProvider> casAuthenticationProvider;
	private final Optional<CasLoginSuccessHandler> casLoginSuccessHandler;

	private boolean basicAuthEnabled;

	private final Config config;

	@PostConstruct
	public void init() {
		basicAuthEnabled = config.getBoolean(Settings.AUTH_HTTP_BASIC_ENABLED, false);
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry sessionRegistry,
												   AuthenticationEntryPoint customEntryPoint,
												   AccessDeniedHandler customAccessDeniedHandler) throws Exception {

		if (corsConfig.isCorsEnabled()) {
			http.cors(Customizer.withDefaults());
		} else {
			http.cors(AbstractHttpConfigurer::disable);
		}

		var entryPoint = basicAuthEnabled ? authChallengeAwareEntryPoint(customEntryPoint) : customEntryPoint;

		http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session
						.sessionConcurrency(concurrency -> concurrency
								.maximumSessions(-1)
								.sessionRegistry(sessionRegistry)
								.expiredSessionStrategy(event ->
										HttpUtil.writeErrorResponseAsJson(event.getResponse(), ErrorResponse.builder()
											.status(HttpStatus.UNAUTHORIZED.value())
											.path(event.getRequest().getRequestURI())
											.error("Session expired")
											.build())))
						.invalidSessionStrategy((request, response) ->
								HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
										.status(HttpStatus.UNAUTHORIZED.value())
										.path(request.getRequestURI())
										.error("Session expired or invalid")
										.build()))
				)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole(UserAuthRole.ADMIN.name())
						.requestMatchers("/management/status/extended").hasRole(UserAuthRole.ADMIN.name())
						.requestMatchers(HttpMethod.POST, "/*/import").hasRole(UserAuthRole.ADMIN.name())
						.requestMatchers("/auth/tokens").hasAnyRole(UserAuthRole.USER.name(), UserAuthRole.ADMIN.name())
						.anyRequest().permitAll()
				)
				.formLogin(login -> login
						.loginPage("/auth/login")
						.loginProcessingUrl("/auth/cookie")
						.successHandler(formLoginAuthenticationSuccessHandler)
						.failureHandler(formLoginAuthenticationFailureHandler)
						.usernameParameter("auth_username")
						.passwordParameter("auth_password")
						.permitAll()
				)
				.logout(logout -> logout
						.logoutUrl("/auth/logout")
						.deleteCookies("auth_token")
						.logoutSuccessHandler((_, response, _) ->
								response.setStatus(HttpStatus.NO_CONTENT.value())
						)
						.permitAll())
				.addFilterBefore(checkUsernamePasswordFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(setUserURIAfterAuthenticationFilter, AnonymousAuthenticationFilter.class)
				.addFilterBefore(ignoreAuthFilter, SetUserURIAfterAuthenticationFilter.class)
				.addFilterAfter(reloadUserPropertiesFilter, SetUserURIAfterAuthenticationFilter.class)
				// below disables the auto redirect to login page when user is not authenticated, instead reply with 401
				.exceptionHandling(e -> e
						.authenticationEntryPoint(entryPoint)
						.accessDeniedHandler(customAccessDeniedHandler)
				);

		if (basicAuthEnabled) {
			log.info("Basic Auth Enabled");
			http.httpBasic(basic -> basic.authenticationEntryPoint(entryPoint));
		} else {
			log.info("Basic Auth Disabled");
		}

		if (samlConfiguration.enabled()) {
			log.info("SAML Auth Enabled");

			// below modifies the login success handler, to set the redirect URL param name
			samlLoginSuccessHandler.setTargetUrlParameter("successurl");
			samlLoginSuccessHandler.setDefaultTargetUrl(samlConfiguration.redirectSuccess().url());

			http.saml2Login(samlLogin -> samlLogin
					.loginPage("/auth/saml")
					.failureUrl(samlConfiguration.redirectFailure().url())
					.authenticationRequestResolver(createCustomResolver())
					.successHandler(samlLoginSuccessHandler));
		} else {
			log.info("SAML Auth Disabled");
		}

		if (casConfiguration.enabled()) {
			log.info("CAS Auth Enabled");

			var casFilter = new CasAuthenticationFilter();
			// CSRF is disabled globally and getParameter() reads form bodies, so pinning to GET
			// prevents a cross-site POST from submitting a stolen ticket.
			RequestMatcher ticketRequired = request -> request.getParameter("ticket") != null;
			casFilter.setRequiresAuthenticationRequestMatcher(new AndRequestMatcher(
					PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/auth/cas"),
					ticketRequired));
			casFilter.setAuthenticationManager(new ProviderManager(
					casAuthenticationProvider.orElseThrow(() -> new IllegalStateException(
							"CAS is enabled but CasAuthenticationProvider bean is missing — check CasConfig."))));

			var handler = casLoginSuccessHandler.orElseThrow(() -> new IllegalStateException(
					"CAS is enabled but CasLoginSuccessHandler bean is missing — check CasConfig."));
			handler.setDefaultTargetUrl(casConfiguration.redirectSuccess().url());
			casFilter.setAuthenticationSuccessHandler(handler);
			// Surface ticket-validation failures at WARN with the full stack trace.
			// SimpleUrlAuthenticationFailureHandler's default logging is at DEBUG level, which
			// makes bad-ticket, CAS-server-down, SSL, and clock-skew errors invisible in production.
			casFilter.setAuthenticationFailureHandler(new SimpleUrlAuthenticationFailureHandler(
					casConfiguration.redirectFailure().url()) {
				@Override
				public void onAuthenticationFailure(HttpServletRequest request,
													HttpServletResponse response,
													AuthenticationException exception) throws IOException, ServletException {
					log.warn("CAS authentication failed at '{}': {}",
							request.getRequestURI(), exception.getMessage(), exception);
					super.onAuthenticationFailure(request, response, exception);
				}
			});

			http.addFilterBefore(casFilter, UsernamePasswordAuthenticationFilter.class);
		} else {
			log.info("CAS Auth Disabled");
		}

		return http.build();
	}

	@Bean
	public AuthenticationEntryPoint customEntryPoint() {
		return (request, response, authException) -> {
			// Delegate the exception to global Exception handler, falling back to a direct envelope
			// write if the resolver returns null (no handler matched) so the client never gets an
			// empty committed response.
			var mv = handlerExceptionResolver.resolveException(request, response, null, authException);
			if (mv == null && !response.isCommitted()) {
				log.warn("AuthenticationEntryPoint: exception resolver did not handle {}", authException.getClass().getName());
				HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.UNAUTHORIZED.value())
						.path(request.getRequestURI())
						.error(HttpStatus.UNAUTHORIZED.getReasonPhrase())
						.build());
			}
		};
	}

	@Bean
	public AccessDeniedHandler customAccessDeniedHandler() {
		return (request, response, accessDeniedException) -> {
			// Delegate the exception to global Exception handler (AppExceptionHandler.handleAccessDeniedException),
			// falling back to a direct envelope write if the resolver returns null.
			var mv = handlerExceptionResolver.resolveException(request, response, null, accessDeniedException);
			if (mv == null && !response.isCommitted()) {
				log.warn("AccessDeniedHandler: exception resolver did not handle {}", accessDeniedException.getClass().getName());
				HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.FORBIDDEN.value())
						.path(request.getRequestURI())
						.error(HttpStatus.FORBIDDEN.getReasonPhrase())
						.build());
			}
		};
	}

	private AuthenticationEntryPoint authChallengeAwareEntryPoint(AuthenticationEntryPoint delegate) {
		return (request, response, authException) -> {
			if (!"false".equalsIgnoreCase(request.getParameter("auth_challenge"))) {
				response.setHeader("Cache-Control", "no-store");
				response.setHeader("WWW-Authenticate", "Basic realm=\"EntryStore\"");
			}
			delegate.commence(request, response, authException);
		};
	}

	@Bean
	public PasswordEncoder passwordEncoder() {

		return new PasswordEncoder() {
			@Override
			public String encode(CharSequence rawPassword) {
				return Password.getSaltedHash(rawPassword.toString());
			}

			@Override
			public boolean matches(CharSequence rawPassword, String encodedPassword) {
				try {
					return Password.check(rawPassword.toString(), encodedPassword);
				} catch (IllegalArgumentException e) {
					return false;
				}
			}
		};
	}

	private Saml2AuthenticationRequestResolver createCustomResolver() {

		if (repo.isEmpty()) {
			throw new RuntimeException("RelyingPartyRegistrationRepository was not injected - missing SAML2 autoconfiguration?");
		}

		var registrationResolver = new DefaultRelyingPartyRegistrationResolver(repo.get());
		var resolver = new OpenSaml4AuthenticationRequestResolver(registrationResolver);

		resolver.setRelayStateResolver(request -> {

			String relayStateToken = RandomStringUtils.secure().nextAlphanumeric(16);

			String successUrl = request.getParameter("successurl");
			String failureUrl = request.getParameter("failureurl");

			if (successUrl != null || failureUrl != null) {
				AuthState authState = new AuthState(successUrl, failureUrl);
				samlAuthStateCache.storeAuthState(relayStateToken, authState);
			}

			return relayStateToken;
		});

		return resolver;
	}

	@Bean
	public FilterRegistrationBean<CheckUsernamePasswordFilter> disableCheckUsernamePasswordFilterAutoRegistration(CheckUsernamePasswordFilter f) {
		FilterRegistrationBean<CheckUsernamePasswordFilter> reg = new FilterRegistrationBean<>(f);
		reg.setEnabled(false);
		return reg;
	}

	@Bean
	public FilterRegistrationBean<SetUserURIAfterAuthenticationFilter> disableSetUserURIAfterAuthenticationFilterAutoRegistration(SetUserURIAfterAuthenticationFilter f) {
		FilterRegistrationBean<SetUserURIAfterAuthenticationFilter> reg = new FilterRegistrationBean<>(f);
		reg.setEnabled(false);
		return reg;
	}

	@Bean
	public FilterRegistrationBean<ReloadUserPropertiesFilter> disableReloadUserPropertiesFilterAutoRegistration(ReloadUserPropertiesFilter f) {
		FilterRegistrationBean<ReloadUserPropertiesFilter> reg = new FilterRegistrationBean<>(f);
		reg.setEnabled(false);
		return reg;
	}

	@Bean
	public FilterRegistrationBean<IgnoreAuthFilter> disableIgnoreAuthFilterAutoRegistration(IgnoreAuthFilter f) {
		FilterRegistrationBean<IgnoreAuthFilter> reg = new FilterRegistrationBean<>(f);
		reg.setEnabled(false);
		return reg;
	}

	@Bean
	public ServletContextInitializer servletContextInitializer(Environment env) {
		return servletContext -> {
			Cookie.SameSite sameSite = Binder.get(env)
					.bind("server.servlet.session.cookie.same-site", Cookie.SameSite.class)
					.orElse(Cookie.SameSite.STRICT);
			if (sameSite == Cookie.SameSite.NONE) {
				servletContext.getSessionCookieConfig().setSecure(true);
			}
		};
	}
}
