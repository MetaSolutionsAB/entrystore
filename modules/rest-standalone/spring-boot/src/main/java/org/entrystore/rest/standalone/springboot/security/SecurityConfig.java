package org.entrystore.rest.standalone.springboot.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.standalone.springboot.model.auth.AuthState;
import org.entrystore.rest.standalone.springboot.model.auth.UserAuthRole;
import org.entrystore.rest.standalone.springboot.service.auth.SamlAuthStateCache;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.DefaultRelyingPartyRegistrationResolver;
import org.springframework.security.saml2.provider.service.web.authentication.OpenSaml4AuthenticationRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.Saml2AuthenticationRequestResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.Optional;

@Slf4j
@EnableMethodSecurity
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	private final BeforeAuthenticationFilter beforeAuthenticationFilter;
	private final PostAuthenticationFilter postAuthenticationFilter;
	private final HandlerExceptionResolver handlerExceptionResolver;
	private final ESAuthenticationFailureHandler authenticationFailureHandler;
	private final ESAuthenticationSuccessHandler authenticationSuccessHandler;

	// SAML-auth related beans
	private final SamlLoginSuccessHandler successHandler;
	private final Optional<RelyingPartyRegistrationRepository> repo; // optional as it will be injected only when Spring's SAML properties are configured
	private final SamlAuthStateCache samlAuthStateCache;

	private boolean basicAuthEnabled;
	private boolean samlAuthEnabled;

	private final Config config;

	@PostConstruct
	public void init() {
		basicAuthEnabled = config.getBoolean(Settings.AUTH_HTTP_BASIC_ENABLED, false);
		samlAuthEnabled = config.getBoolean(Settings.AUTH_SAML_ENABLED, false);
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

		http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session
						.maximumSessions(-1)
						.sessionRegistry(sessionRegistry())
						.expiredSessionStrategy(new ESExpiredSessionStrategy()))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/error").permitAll()
						.requestMatchers("/echo").permitAll() // needs textarea response, otherwise default Spring-boot Unauthorized json response is returned
						.requestMatchers("/auth/login", "/auth/signup", "/auth/pwreset", "/auth/saml").permitAll()
						.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs*/**").permitAll()
						.requestMatchers("/management/status").permitAll()
						.requestMatchers("/management/status/extended").hasRole(UserAuthRole.ADMIN.name())
						.anyRequest().authenticated()
				)
				.formLogin(login -> login
						.loginPage("/auth/login")
						.loginProcessingUrl("/auth/cookie")
						.successHandler(authenticationSuccessHandler)
						.failureHandler(authenticationFailureHandler)
						.usernameParameter("auth_username")
						.passwordParameter("auth_password")
						.permitAll()
				)
				.logout(logout -> logout
						.logoutUrl("/auth/logout")
						.deleteCookies("auth_token")
						.permitAll())
				.addFilterBefore(beforeAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(postAuthenticationFilter, AnonymousAuthenticationFilter.class)
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

		if (samlAuthEnabled) {
			log.info("SAML Auth Enabled");

			// below modifies the login success handler, to set the redirect URL param name
			successHandler.setTargetUrlParameter("successurl");
			successHandler.setDefaultTargetUrl("/management/status");

			http.saml2Login(samlLogin -> samlLogin
					.loginPage("/auth/saml")
					.authenticationRequestResolver(createCustomResolver())
					.successHandler(successHandler));
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

	@Bean
	public SessionRegistry sessionRegistry() {
		return new SessionRegistryImpl();
	}

	@Bean
	public HttpSessionEventPublisher httpSessionEventPublisher() {
		return new HttpSessionEventPublisher();
	}
}
