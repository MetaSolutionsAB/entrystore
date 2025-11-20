package org.entrystore.rest.standalone.springboot.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.standalone.springboot.model.auth.UserAuthRole;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Slf4j
@EnableMethodSecurity
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	private final BeforeAuthenticationFilter beforeAuthenticationFilter;
	private final PostAuthenticationFilter postAuthenticationFilter;
	private final SamlLoginSuccessHandler successHandler;
	private final HandlerExceptionResolver handlerExceptionResolver;
	private final ESAuthenticationFailureHandler authenticationFailureHandler;
	private final ESAuthenticationSuccessHandler authenticationSuccessHandler;

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
						.defaultSuccessUrl("/management/status")
						.successHandler(authenticationSuccessHandler)
						.failureHandler(authenticationFailureHandler)
						.usernameParameter("auth_username")
						.passwordParameter("auth_password")
						.permitAll()
				)
				.logout(logout -> logout
						.logoutUrl("/auth/logout")
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
				boolean matches;
				try {
					matches = Password.check(rawPassword.toString(), encodedPassword);
				} catch (IllegalArgumentException e) {
					log.warn(e.getMessage());
					matches = false;
				}
				return matches;
			}
		};
	}
}
