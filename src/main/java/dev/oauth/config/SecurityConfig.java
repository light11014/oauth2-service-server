package dev.oauth.config;

import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final OAuth2AuthorizedClientService authorizedClientService;

    @Value("${app.frontend-uri}")
    private String frontendUri;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors
                        .configurationSource(request -> {
                            CorsConfiguration config = new CorsConfiguration();
                            config.addAllowedOrigin(frontendUri);
                            config.addAllowedMethod("*");
                            config.addAllowedHeader("*");
                            config.setAllowCredentials(true);
                            return config;
                        })
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestRepository(new CookieAuthorizationRequestRepository())
                        )
                        .successHandler((request, response, authentication) -> {
                            OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                                    "service-demo",
                                    authentication.getName()
                            );

                            if (client == null) {
                                response.sendRedirect(frontendUri + "/login?error=no_client");
                                return;
                            }

                            String accessToken = client.getAccessToken().getTokenValue();

                            Cookie cookie = new Cookie("access_token", accessToken);
                            cookie.setHttpOnly(true);
                            cookie.setSecure(false);
                            cookie.setPath("/");
                            cookie.setMaxAge(3600);
                            response.addCookie(cookie);

                            response.sendRedirect(frontendUri + "/home");
                        })
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())   // jwk-set-uri는 yml에서 주입
                        .bearerTokenResolver(request -> {
                            if (request.getCookies() != null) {
                                for (Cookie cookie : request.getCookies()) {
                                    if ("access_token".equals(cookie.getName())) {
                                        return cookie.getValue();
                                    }
                                }
                            }
                            return null;
                        })
                )
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .deleteCookies("access_token")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.sendRedirect(frontendUri + "/login")
                        )
                );

        return http.build();
    }
}