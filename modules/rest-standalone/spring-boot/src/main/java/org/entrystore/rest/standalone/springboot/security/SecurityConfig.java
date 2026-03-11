package org.entrystore.rest.standalone.springboot.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.standalone.springboot.configuration.CorsConfig;
import org.entrystore.rest.standalone.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.standalone.springboot.model.auth.AuthState;
import org.entrystore.rest.standalone.springboot.model.auth.UserAuthRole;
import org.entrystore.rest.standalone.springboot.service.auth.SamlAuthStateCache;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.DefaultRelyingPartyRegistrationResolver;
import org.springframework.security.saml2.provider.service.web.authentication.OpenSaml4AuthenticationRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.Saml2AuthenticationRequestResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.Optional;

@Slf4j
@EnableMethodSecurity
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	private final CheckUsernamePasswordFilter checkUsernamePasswordFilter;
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

	private boolean basicAuthEnabled;

	private final Config config;

	@PostConstruct
	public void init() {
		basicAuthEnabled = config.getBoolean(Settings.AUTH_HTTP_BASIC_ENABLED, false);
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry sessionRegistry) throws Exception {

		if (corsConfig.isCorsEnabled()) {
			http.cors(Customizer.withDefaults());
		} else {
			http.cors(AbstractHttpConfigurer::disable);
		}

		http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session
						.sessionConcurrency(concurrency -> concurrency
								.maximumSessions(-1)
								.sessionRegistry(sessionRegistry)
								.expiredSessionStrategy(event ->
										event.getResponse().sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session expired")))
						.invalidSessionStrategy((request, response) ->
								response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session expired or invalid"))
				)
				.authorizeHttpRequests(auth -> auth
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
						.permitAll())
				.addFilterBefore(checkUsernamePasswordFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(setUserURIAfterAuthenticationFilter, AnonymousAuthenticationFilter.class)
				.addFilterAfter(reloadUserPropertiesFilter, SetUserURIAfterAuthenticationFilter.class)
				// below disables the auto redirect to login page when user is not authenticated, instead reply with 401
				.exceptionHandling(e -> e
						.authenticationEntryPoint(customEntryPoint())
				);

		if (basicAuthEnabled) {
			log.info("Basic Auth Enabled");
			http.httpBasic(Customizer.withDefaults());
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

		return http.build();
	}

	@Bean
	public AuthenticationEntryPoint customEntryPoint() {
		return (request, response, authException) -> {
			// Delegate the exception to global Exception handler
			handlerExceptionResolver.resolveException(request, response, null, authException);
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
