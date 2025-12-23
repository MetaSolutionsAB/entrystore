package org.entrystore.rest.standalone.springboot.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.apache.commons.text.RandomStringGenerator;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.standalone.springboot.util.AesGcmEncryptionUtil;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.NoSuchPaddingException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.apache.commons.text.CharacterPredicates.DIGITS;
import static org.entrystore.repository.config.Settings.AUTH_COOKIE_REFRESH_EXPIRATION_ON_ACCESS;

@Service
@RequiredArgsConstructor
public class ESTokenService {

	private boolean configTokenUpdateExpiry;
	private final ESUserDetailsService userDetailsService;
	private final Config config;
	private AesGcmEncryptionUtil aesGcmEncryptionUtil;

	private final RandomStringGenerator generator = new RandomStringGenerator.Builder()
			.withinRange('0', 'z')
			.filteredBy(DIGITS)
			.get();

	@PostConstruct
	public void init() throws NoSuchPaddingException, NoSuchAlgorithmException {
		this.configTokenUpdateExpiry = config.getBoolean(AUTH_COOKIE_REFRESH_EXPIRATION_ON_ACCESS, true);
		String secretString = config.getString(Settings.AUTH_COOKIE_SECRET, generator.generate(32));
		this.aesGcmEncryptionUtil = new AesGcmEncryptionUtil(secretString.getBytes(StandardCharsets.UTF_8));
	}

	public String generateToken(Authentication authentication, long expiry) {
		String payload = expiry + "|" + authentication.getName() + ":" + String.join(",",
				authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());

		return aesGcmEncryptionUtil.encrypt(payload);
	}

	public Authentication getAuthentication(String token) {
		String payload = aesGcmEncryptionUtil.decrypt(token);

		if (payload != null) {
			String[] parts = payload.split("\\|");
			long expiry = Long.parseLong(parts[0]);

			if (Instant.now().getEpochSecond() > expiry) {
				return null; // Token has expired
			}

			String[] userData = parts[1].split(":");
			String username = userData[0];
			List<SimpleGrantedAuthority> authorities = Stream.of(userData[1].split(","))
					.map(SimpleGrantedAuthority::new)
					.toList();

			ESUserDetails userDetails = userDetailsService.loadUserByUsername(username);
			return new UsernamePasswordAuthenticationToken(userDetails, null, authorities);
		}

		return null; // Bad token
	}
}
