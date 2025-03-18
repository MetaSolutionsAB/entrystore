package org.entrystore.rest.standalone.springboot.security;

import org.entrystore.repository.security.Password;
import org.entrystore.rest.standalone.springboot.model.UserAuthRole;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/error").permitAll()
				.requestMatchers("/management/status").permitAll()
				.requestMatchers("/auth/login", "/auth/cookie", "/auth/signup", "/auth/logout").permitAll()
				.requestMatchers("/management/status/extended").hasRole(UserAuthRole.ADMIN.name())
				.anyRequest().authenticated()
			)
			.formLogin(login -> login
				.loginPage("/auth/login")
				.loginProcessingUrl("/auth/cookie")
				.defaultSuccessUrl("/management/status")
				.usernameParameter("auth_username")
				.passwordParameter("auth_password")
				.permitAll()
			)
			.logout(logout -> logout
				.logoutUrl("/auth/logout")
				.permitAll())
			.httpBasic(Customizer.withDefaults());
		return http.build();
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
				return Password.check(rawPassword.toString(), encodedPassword);
			}
		};
	}
}
