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

import com.github.benmanes.caffeine.cache.Ticker;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.springboot.configuration.CasCustomConfiguration;
import org.entrystore.rest.springboot.configuration.CorsProperties;
import org.entrystore.rest.springboot.configuration.HttpBasicAuthConfiguration;
import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration;
import org.entrystore.rest.springboot.configuration.PasswordLoginListProperties;
import org.entrystore.rest.springboot.configuration.PasswordLoginMode;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.filter.CheckUsernamePasswordFilter;
import org.entrystore.rest.springboot.filter.CsrfCookieFilter;
import org.entrystore.rest.springboot.filter.IgnoreAuthFilter;
import org.entrystore.rest.springboot.filter.ReloadUserPropertiesFilter;
import org.entrystore.rest.springboot.filter.SetUserURIAfterAuthenticationFilter;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.model.auth.UserAuthRole;
import org.entrystore.rest.springboot.service.OidcAuthService;
import org.entrystore.rest.springboot.service.auth.OidcAuthStateCache;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.context.properties.bind.BindException;
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
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.DefaultRelyingPartyRegistrationResolver;
import org.springframework.security.saml2.provider.service.web.OpenSaml5AuthenticationTokenConverter;
import org.springframework.security.saml2.provider.service.web.authentication.OpenSaml5AuthenticationRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.Saml2AuthenticationRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.Saml2WebSsoAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.SessionManagementFilter;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
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

	private final CorsProperties corsProperties;
	private final ErrorResponseWriter errorResponseWriter;

	private final CsrfRequestMatcher csrfRequestMatcher;
	private final CsrfCookieFilter csrfCookieFilter;

	private final AuthTokenCookies authTokenCookies;

	// SAML-auth related beans (handlers optional — only present when entrystore.auth.saml.enabled=true)
	private final SamlCustomConfiguration samlConfiguration;
	private final Optional<SamlLoginSuccessHandler> samlLoginSuccessHandler;
	private final Optional<SamlLoginFailureHandler> samlLoginFailureHandler;
	private final Optional<RelyingPartyRegistrationRepository> repo; // optional as it will be injected only when Spring's SAML properties are configured
	private final SamlRelayStateResolver samlRelayStateResolver;
	private final CacheSaml2AuthenticationRequestRepository saml2AuthenticationRequestRepository;

	// CAS-auth related beans (optional — only present when entrystore.auth.cas.enabled=true)
	private final CasCustomConfiguration casConfiguration;
	private final Optional<CasAuthenticationProvider> casAuthenticationProvider;
	private final Optional<CasLoginSuccessHandler> casLoginSuccessHandler;

	// OIDC-auth related beans (success handler optional — only present when entrystore.auth.oidc.enabled=true)
	private final OidcCustomConfiguration oidcConfiguration;
	private final Optional<OidcLoginSuccessHandler> oidcLoginSuccessHandler;
	private final Optional<ClientRegistrationRepository> clientRegistrationRepository; // optional as it will be injected only when Spring's OAuth2 client registrations are configured
	private final OidcAuthService oidcAuthService;
	private final OidcAuthStateCache oidcAuthStateCache;
	private final CacheOAuth2AuthorizationRequestRepository oauth2AuthorizationRequestRepository;

	private final HttpBasicAuthConfiguration httpBasicConfig;
	private final PasswordLoginMode passwordLoginMode;
	private final PasswordLoginListProperties passwordLoginLists;

	private final Environment environment;

	@Value("${server.servlet.session.cookie.secure:true}")
	private boolean sessionCookieSecure;

	@Value("${entrystore.csrf.cookie-name:XSRF-TOKEN}")
	private String csrfCookieName;

	// Default is false until the common EntryStore clients echo the XSRF-TOKEN cookie as an
	// X-XSRF-TOKEN header on mutations — see ENTRYSTORE-1008 for the compatibility discussion.
	@Value("${entrystore.csrf.enabled:false}")
	private boolean csrfEnabled;

	// When false, an unknown or expired session cookie is expired and the request is served as guest instead of 401
	@Value("${entrystore.auth.cookie.invalid-token-error:true}")
	private boolean invalidTokenError;

	private Cookie.SameSite sessionCookieSameSite;

	@PostConstruct
	public void init() {
		sessionCookieSameSite = resolveSessionCookieSameSite(environment);
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry sessionRegistry,
												   AuthenticationEntryPoint customEntryPoint,
												   AccessDeniedHandler customAccessDeniedHandler) throws Exception {

		if (corsProperties.enabled()) {
			http.cors(Customizer.withDefaults());
		} else {
			http.cors(AbstractHttpConfigurer::disable);
		}

		if (csrfEnabled) {
			http
					.csrf(csrf -> csrf
							.csrfTokenRepository(csrfTokenRepository())
							.csrfTokenRequestHandler(new HeaderOnlyForMultipartCsrfTokenRequestHandler())
							.requireCsrfProtectionMatcher(csrfRequestMatcher))
					.addFilterAfter(csrfCookieFilter, CsrfFilter.class);
		} else {
			// Migration escape hatch for clients that do not yet echo the XSRF-TOKEN cookie as
			// X-XSRF-TOKEN. CsrfCookieFilter is deliberately not registered either, so no
			// XSRF-TOKEN cookie is issued while enforcement is off.
			log.warn("CSRF protection is DISABLED (entrystore.csrf.enabled=false). Cookie-authenticated "
					+ "sessions are exposed to cross-site request forgery; use only as a temporary measure "
					+ "until all clients send the X-XSRF-TOKEN header.");
			http.csrf(AbstractHttpConfigurer::disable);
		}

		var entryPoint = isHttpBasicEnabled() ? authChallengeAwareEntryPoint(customEntryPoint) : customEntryPoint;

		http
				// Disable Spring Security's default CacheControlHeadersWriter so that CacheControlFilter
				// governs the Cache-Control / Pragma / Expires headers end-to-end. The default writer would
				// otherwise stamp "no-cache, no-store, max-age=0, must-revalidate" on every response,
				// including permit-all public endpoints — which contradicts the per-request policy this app
				// needs (private,no-cache for authenticated; no header for anonymous so static and
				// controller-set values can pass through unchanged).
				.headers(headers -> headers.cacheControl(HeadersConfigurer.CacheControlConfig::disable))
				// Spring's default repositories, set explicitly so SessionManagementFilter also sees the request-scoped
				// HTTP Basic context and does not start a session (and auth_token cookie) for it, as in 5.x
				.securityContext(context -> context.securityContextRepository(sessionAndRequestContextRepository()))
				// Nothing resumes a request after login (the entry point answers 401), and saving one would start a
				// session, and thus an auth_token cookie, for a guest's browser request to a protected page
				.requestCache(RequestCacheConfigurer::disable)
				.sessionManagement(session -> {
					// ConcurrentSessionFilter runs the logout handlers, and thereby expires the cookie, before this strategy
					session.sessionConcurrency(concurrency -> concurrency
							.maximumSessions(-1)
							.sessionRegistry(sessionRegistry)
							.expiredSessionStrategy(event -> {
								if (!invalidTokenError) {
									event.getFilterChain().doFilter(event.getRequest(), event.getResponse());
									return;
								}
								errorResponseWriter.writeErrorResponseAsJson(event.getResponse(), ErrorResponse.builder()
										.status(HttpStatus.UNAUTHORIZED.value())
										.path(event.getRequest().getRequestURI())
										.error("Session expired")
										.build());
							}));
					if (invalidTokenError) {
						session.invalidSessionStrategy((request, response) -> {
							authTokenCookies.expireAll(request, response);
							errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
									.status(HttpStatus.UNAUTHORIZED.value())
									.path(request.getRequestURI())
									.error("Session expired or invalid")
									.build());
						});
					}
				})
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole(UserAuthRole.ADMIN.name())
						.requestMatchers("/management/status/extended").hasRole(UserAuthRole.ADMIN.name())
						.requestMatchers(HttpMethod.POST, "/*/import").hasRole(UserAuthRole.ADMIN.name())
						.requestMatchers("/auth/tokens").hasAnyRole(UserAuthRole.USER.name(), UserAuthRole.ADMIN.name())
						.anyRequest().permitAll()
				)
				.logout(logout -> logout
						// Pin logout to POST so a same-site `<a href="/auth/logout">` or `<img src=…>`
						// from a relaxed-SameSite cookie context cannot force-log-out the user.
						// CsrfRequestMatcher then requires a valid X-XSRF-TOKEN on the POST.
						.logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/logout"))
						.addLogoutHandler(authTokenCookies)
						.logoutSuccessHandler((_, response, _) ->
								response.setStatus(HttpStatus.NO_CONTENT.value())
						)
						.permitAll())
				.addFilterAfter(setUserURIAfterAuthenticationFilter, AnonymousAuthenticationFilter.class)
				.addFilterBefore(ignoreAuthFilter, SetUserURIAfterAuthenticationFilter.class)
				.addFilterAfter(reloadUserPropertiesFilter, SetUserURIAfterAuthenticationFilter.class)
				// below disables the auto redirect to login page when user is not authenticated, instead reply with 401
				.exceptionHandling(e -> e
						.authenticationEntryPoint(entryPoint)
						.accessDeniedHandler(customAccessDeniedHandler)
				);

		if (!invalidTokenError) {
			// Without an invalid-session strategy, SessionManagementFilter lets the request continue as guest
			http.addFilterBefore(new InvalidSessionCookieFilter(authTokenCookies), SessionManagementFilter.class);
		}
		if (!authTokenCookies.isRefreshExpirationOnAccess()) {
			http.addFilterBefore(new SessionLifetimeFilter(authTokenCookies), SecurityContextHolderFilter.class);
		}

		if (passwordLoginMode == PasswordLoginMode.OFF) {
			// DisabledRouteFilter answers /auth/cookie and /auth/login with 404 before this chain runs
			log.info("Password login disabled");
		} else {
			http
					.formLogin(login -> login
							.loginPage("/auth/login")
							.loginProcessingUrl("/auth/cookie")
							.successHandler(formLoginAuthenticationSuccessHandler)
							.failureHandler(formLoginAuthenticationFailureHandler)
							.usernameParameter("auth_username")
							.passwordParameter("auth_password")
							.permitAll()
					)
					.addFilterBefore(checkUsernamePasswordFilter, UsernamePasswordAuthenticationFilter.class);
		}

		if (isHttpBasicEnabled()) {
			log.info("Basic Auth Enabled (credential cache TTL={}, max entries={})",
					httpBasicConfig.cache().ttl(), httpBasicConfig.cache().maxSize());
			http.httpBasic(basic -> {
				basic.authenticationEntryPoint(entryPoint);
				if (passwordLoginMode == PasswordLoginMode.WHITELIST) {
					var whitelist = List.copyOf(passwordLoginLists.whitelist().values());
					// An anonymous class, not a lambda: see the SAML branch below.
					basic.withObjectPostProcessor(new ObjectPostProcessor<BasicAuthenticationFilter>() {
						@Override
						public <O extends BasicAuthenticationFilter> O postProcess(O filter) {
							filter.setAuthenticationConverter(new WhitelistBasicAuthenticationConverter(whitelist));
							return filter;
						}
					});
				}
			});
		} else if (httpBasicConfig.enabled()) {
			log.warn("Basic Auth Disabled: entrystore.auth.password=off overrides "
					+ "entrystore.auth.http-basic.enabled=true");
		} else {
			log.info("Basic Auth Disabled");
		}

		var cacheAwareRedirectStrategy = new CacheAwareRedirectStrategy();

		if (samlConfiguration.enabled()) {
			log.info("SAML Auth Enabled");
			samlConfiguration.idp().forEach((id, idp) ->
					log.info("SAML IdP \"{}\" - Domains: {}, Auto Provisioning: {}", id, idp.domains(), idp.userAutoProvisioning()));
			log.info("SAML Default IdP: {}", samlConfiguration.defaultIdp());
			samlConfiguration.redirectDomainWhitelist().forEach(domain -> log.info("Allowed domain for redirects: {}", domain));

			var samlHandler = samlLoginSuccessHandler.orElseThrow(() -> new IllegalStateException(
					"SAML is enabled but SamlLoginSuccessHandler bean is missing — check the " +
							"entrystore.auth.saml.enabled binding."));
			// Custom success URLs flow through the validated relay-state cache (SamlRelayStateResolver);
			// the handler must NOT read a request parameter for the target (open-redirect, ENTRYSTORE-996),
			// so only the trusted default target is configured here.
			samlHandler.setDefaultTargetUrl(samlConfiguration.redirectSuccess().url());
			// Stamp Cache-Control: private, no-store on the 302 carrying the session Set-Cookie
			// before sendRedirect commits the response — CacheControlFilter's post-chain check
			// cannot run after a committed response, so the redirect strategy closes that gap.
			samlHandler.setRedirectStrategy(cacheAwareRedirectStrategy);
			var samlFailureHandler = samlLoginFailureHandler.orElseThrow(() -> new IllegalStateException(
					"SAML is enabled but SamlLoginFailureHandler bean is missing — check the " +
							"entrystore.auth.saml.enabled binding."));

			// Also processes SAML responses posted to the 5.x assertion consumer service (POST /auth/saml?idp=<id>).
			var acsMatcher = new SamlAcsRequestMatcher();
			http.saml2Login(samlLogin -> samlLogin
					.loginPage("/auth/saml")
					.failureHandler(samlFailureHandler)
					.authenticationRequestResolver(createCustomResolver())
					.authenticationConverter(createAcsTokenConverter(acsMatcher))
					.successHandler(samlHandler)
					// An anonymous class, not a lambda: the configurer applies a post-processor whose type
					// argument it cannot resolve to every object it builds.
					.withObjectPostProcessor(new ObjectPostProcessor<Saml2WebSsoAuthenticationFilter>() {
						@Override
						public <O extends Saml2WebSsoAuthenticationFilter> O postProcess(O filter) {
							filter.setRequiresAuthenticationRequestMatcher(acsMatcher);
							return filter;
						}
					}));
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
			// See the SAML branch above for the rationale.
			handler.setRedirectStrategy(cacheAwareRedirectStrategy);
			casFilter.setAuthenticationSuccessHandler(handler);
			casFilter.setAuthenticationFailureHandler(
					new SsoLoginFailureHandler("CAS", casConfiguration.redirectFailure().url()));

			http.addFilterBefore(casFilter, UsernamePasswordAuthenticationFilter.class);
		} else {
			log.info("CAS Auth Disabled");
		}

		if (oidcConfiguration.enabled()) {
			log.info("OIDC Auth Enabled");
			oidcConfiguration.provider().forEach((id, provider) ->
					log.info("OIDC provider \"{}\" - Domains: {}, Auto Provisioning: {}, Username Claim: {}",
							id, provider.domains(), provider.userAutoProvisioning(), provider.usernameClaim()));
			log.info("OIDC Default Provider: {}", oidcConfiguration.defaultProvider());
			oidcConfiguration.redirectDomainWhitelist().forEach(domain -> log.info("Allowed domain for redirects: {}", domain));

			var oidcHandler = oidcLoginSuccessHandler.orElseThrow(() -> new IllegalStateException(
					"OIDC is enabled but OidcLoginSuccessHandler bean is missing — check the " +
							"entrystore.auth.oidc.enabled binding."));
			// Custom success URLs flow through the validated state-keyed cache (OidcAuthorizationRequestResolver);
			// the handler must NOT read a request parameter for the target — see the SAML branch above for the
			// ENTRYSTORE-996 open-redirect rationale. Only the trusted default target is configured here.
			oidcHandler.setDefaultTargetUrl(oidcConfiguration.redirectSuccess().url());
			// See the SAML branch above for the Cache-Control rationale.
			oidcHandler.setRedirectStrategy(cacheAwareRedirectStrategy);

			var registrations = clientRegistrationRepository.orElseThrow(() -> new IllegalStateException(
					"OIDC is enabled but no ClientRegistrationRepository is available — configure at least " +
							"one client under spring.security.oauth2.client.registration.*"));

			http.oauth2Login(oidcLogin -> oidcLogin
					.loginPage("/auth/oidc")
					.authorizationEndpoint(authorization -> authorization
							.authorizationRequestResolver(new OidcAuthorizationRequestResolver(
									registrations, oidcAuthService, oidcAuthStateCache))
							// Keyed by the state parameter instead of the HTTP session so the flow survives
							// SameSite=Strict session cookies on the cross-site callback redirect.
							.authorizationRequestRepository(oauth2AuthorizationRequestRepository))
					// Names the principal after the per-provider username claim (default: email), so
					// authentication.getName() resolves the EntryStore username consistently in the
					// success handler and SetUserURIAfterAuthenticationFilter.
					.userInfoEndpoint(userInfo -> userInfo.oidcUserService(new UsernameClaimOidcUserService(oidcAuthService)))
					.successHandler(oidcHandler)
					.failureHandler(new SsoLoginFailureHandler("OIDC", oidcConfiguration.redirectFailure().url())));
		} else {
			log.info("OIDC Auth Disabled");
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
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
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
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.FORBIDDEN.value())
						.path(request.getRequestURI())
						.error(HttpStatus.FORBIDDEN.getReasonPhrase())
						.build());
			}
		};
	}

	/** HTTP Basic carries a password, so password login off disables it too, as in 5.x. */
	private boolean isHttpBasicEnabled() {
		return httpBasicConfig.enabled() && passwordLoginMode != PasswordLoginMode.OFF;
	}

	private AuthenticationEntryPoint authChallengeAwareEntryPoint(AuthenticationEntryPoint delegate) {
		return (request, response, authException) -> {
			if (!"false".equalsIgnoreCase(HttpUtil.getQueryParameter(request, "auth_challenge"))) {
				response.setHeader("Cache-Control", "no-store");
				response.setHeader("WWW-Authenticate", "Basic realm=\"EntryStore\"");
			}
			delegate.commence(request, response, authException);
		};
	}

	@Bean
	public PasswordEncoder passwordEncoder(Ticker ticker) {
		return buildPasswordEncoder(
				isHttpBasicEnabled(),
				httpBasicConfig.cache().ttl(),
				httpBasicConfig.cache().maxSize(),
				ticker);
	}

	// Pure function of its arguments — no bean state — so it stays static and unit-testable
	// directly from SecurityConfigTest without standing up the full @Configuration bean graph.
	static PasswordEncoder buildPasswordEncoder(boolean basicAuthEnabled, Duration ttl, long maxSize, Ticker ticker) {
		PasswordEncoder pbkdf2 = pbkdf2PasswordEncoder();
		return basicAuthEnabled
				? new CachingPasswordEncoder(pbkdf2, ttl, maxSize, ticker)
				: pbkdf2;
	}

	private static PasswordEncoder pbkdf2PasswordEncoder() {
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
		var registrationResolver = new DefaultRelyingPartyRegistrationResolver(registrationRepository());
		var resolver = new OpenSaml5AuthenticationRequestResolver(registrationResolver);

		resolver.setRelayStateResolver(samlRelayStateResolver);

		return resolver;
	}

	/**
	 * The converter Spring would build by default, but matching {@code acsMatcher}. A converter passed to
	 * the configurer is used as is, so the request repository has to be set here as well.
	 */
	private OpenSaml5AuthenticationTokenConverter createAcsTokenConverter(SamlAcsRequestMatcher acsMatcher) {
		var converter = new OpenSaml5AuthenticationTokenConverter(registrationRepository());
		converter.setRequestMatcher(acsMatcher);
		converter.setAuthenticationRequestRepository(saml2AuthenticationRequestRepository);
		return converter;
	}

	private RelyingPartyRegistrationRepository registrationRepository() {
		return repo.orElseThrow(() -> new IllegalStateException(
				"RelyingPartyRegistrationRepository was not injected - missing SAML2 autoconfiguration?"));
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
	public FilterRegistrationBean<CsrfCookieFilter> disableCsrfCookieFilterAutoRegistration() {
		FilterRegistrationBean<CsrfCookieFilter> reg = new FilterRegistrationBean<>(csrfCookieFilter);
		reg.setEnabled(false);
		return reg;
	}

	private CookieCsrfTokenRepository csrfTokenRepository() {
		Objects.requireNonNull(sessionCookieSameSite,
				"init() must run before csrfTokenRepository() — sessionCookieSameSite is null");
		var repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repo.setCookieName(csrfCookieName);
		boolean secure = requiresSecureCookie(sessionCookieSecure, sessionCookieSameSite);
		repo.setCookieCustomizer(builder -> builder
				.sameSite(sessionCookieSameSite.attributeValue())
				.secure(secure));
		return repo;
	}

	// Package-private static so SecurityConfigTest can drive the four (secure × sameSite) combinations
	// without constructing the full @RequiredArgsConstructor bean graph. The rule: a cookie must be
	// flagged Secure when the operator explicitly configured Secure OR when SameSite=None (the latter
	// is mandated by all modern browsers; without Secure they silently drop the cookie).
	static boolean requiresSecureCookie(boolean configuredSecure, Cookie.SameSite sameSite) {
		return configuredSecure || sameSite == Cookie.SameSite.NONE;
	}

	// Package-private static so SecurityConfigTest can drive it with a MockEnvironment without
	// constructing the full @RequiredArgsConstructor bean graph.
	static Cookie.SameSite resolveSessionCookieSameSite(Environment environment) {
		// Resolved once in init() and cached in sessionCookieSameSite so csrfTokenRepository() and
		// servletContextInitializer() agree on the same value.
		// Binder applies Spring Boot's relaxed binding (case-insensitive enum match) and throws
		// BindException on typos like "Nonee" — which we surface as a WARN before defaulting to
		// STRICT, so silent misconfiguration cannot leave Secure=false on a cookie the operator
		// intended to flag SameSite=NONE.
		try {
			return Binder.get(environment)
					.bind("server.servlet.session.cookie.same-site", Cookie.SameSite.class)
					.orElse(Cookie.SameSite.STRICT);
		} catch (BindException e) {
			String raw = environment.getProperty("server.servlet.session.cookie.same-site");
			log.warn("Invalid server.servlet.session.cookie.same-site value '{}'; falling back to STRICT. "
					+ "Valid values: NONE, LAX, STRICT.", raw, e);
			return Cookie.SameSite.STRICT;
		}
	}

	private static SecurityContextRepository sessionAndRequestContextRepository() {
		var sessionRepository = new HttpSessionSecurityContextRepository();
		sessionRepository.setDisableUrlRewriting(true);
		return new DelegatingSecurityContextRepository(sessionRepository, new RequestAttributeSecurityContextRepository());
	}

	@Bean
	public ServletContextInitializer servletContextInitializer() {
		return servletContext -> {
			servletContext.getSessionCookieConfig().setPath(authTokenCookies.getIssuingPath());
			servletContext.getSessionCookieConfig().setMaxAge(authTokenCookies.cookieMaxAgeSeconds());
			if (sessionCookieSameSite == Cookie.SameSite.NONE) {
				servletContext.getSessionCookieConfig().setSecure(true);
			}
		};
	}
}
