# service-demo

Spring Boot 기반의 OAuth2 **리소스 서버(Resource Server)** 데모 프로젝트입니다.  
별도로 구성된 Authorization Server(포트 9000)가 발급한 JWT를 검증하고, 보호된 API를 제공합니다.

---

## 기술 스택

| 항목 | 내용 |
|---|---|
| Java | 17 |
| Spring Boot | 4.0.3 |
| Spring Security | OAuth2 Client + Resource Server |
| Build | Gradle |

---

## 역할 및 책임

이 서버는 두 가지 역할을 동시에 수행합니다.

### 1. OAuth2 클라이언트 (Authorization Code Flow 개시)

- 브라우저(프론트엔드)의 로그인 요청을 받아 Authorization Server의 인가 엔드포인트로 리다이렉트합니다.
- Authorization Code를 콜백으로 받아 Authorization Server에서 Access Token을 교환합니다.
- 발급받은 Access Token을 `HttpOnly` 쿠키(`access_token`)에 저장해 프론트엔드로 전달합니다.

### 2. OAuth2 리소스 서버 (JWT 검증)

- 이후 모든 API 요청에서 `access_token` 쿠키를 꺼내 Bearer Token으로 사용합니다.
- Authorization Server의 JWK Set(`/oauth2/jwks`)으로 JWT 서명을 검증합니다.
- 검증된 JWT의 클레임을 `@AuthenticationPrincipal Jwt`로 컨트롤러에 주입합니다.

---

## 주요 구성

### SecurityConfig

```
OAuth2 Login (클라이언트)
  └─ authorizationRequestRepository → CookieAuthorizationRequestRepository
  └─ successHandler → access_token 쿠키 발급 → 프론트엔드 /home 리다이렉트

OAuth2 Resource Server
  └─ bearerTokenResolver → access_token 쿠키에서 토큰 추출
  └─ JWT 검증 → jwk-set-uri (application.yml에서 주입)

Logout
  └─ POST /api/auth/logout → access_token 쿠키 삭제 → 프론트엔드 /login 리다이렉트
```

### CookieAuthorizationRequestRepository

OAuth2 인가 요청 상태(`state`, `code_challenge` 등)를 **세션 대신 쿠키**에 저장합니다.  
→ [트러블슈팅](#트러블슈팅) 참고

### UserController

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/me` | JWT의 `sub` 클레임 반환 |

---

## 환경 설정

### application.yml

민감 정보는 모두 환경변수 플레이스홀더로 관리합니다.

```yaml
${AUTH_SERVER_URI}     # Authorization Server 주소
${OAUTH2_CLIENT_ID}    # 클라이언트 ID
${OAUTH2_CLIENT_SECRET}# 클라이언트 시크릿
${APP_BASE_URI}        # 이 서버의 주소 (redirect-uri 구성용)
${FRONTEND_URI}        # 프론트엔드 주소 (CORS, 리다이렉트용)
```

### 로컬 개발 환경

`application-local.yml`을 `src/main/resources/`에 생성합니다. (`.gitignore` 필수)

```yaml
# application-local.yml
AUTH_SERVER_URI: http://localhost:9000
OAUTH2_CLIENT_ID: your-client-id
OAUTH2_CLIENT_SECRET: your-client-secret
APP_BASE_URI: http://localhost:8080
FRONTEND_URI: http://localhost:5173
```

프로파일 활성화:

```bash
# CLI
./gradlew bootRun --args='--spring.profiles.active=local'

# IntelliJ
Run Configuration → Active profiles: local
```

---

## 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

기본 포트: `8080`

---

## 트러블슈팅

### `CookieAuthorizationRequestRepository` — 세션리스 환경에서 인가 요청 상태 유실

#### 문제

Spring Security의 기본 `OAuth2AuthorizationRequestRepository` 구현체는 인가 요청 객체(`OAuth2AuthorizationRequest`)를 **HttpSession**에 저장합니다.  
프론트엔드가 별도 Origin(`localhost:5173`)에서 동작하거나, 서버를 Stateless하게 운영할 경우 Authorization Server 콜백 시점에 세션이 유실되어 아래 에러가 발생합니다.

```
OAuth2AuthorizationRequest is missing or has been cleared
```

콜백 URL(`/login/oauth2/code/service-demo`)에 `code`와 `state`가 정상적으로 도착하더라도 서버 측에서 저장해둔 원본 요청을 찾지 못해 인증이 실패합니다.

#### 원인

`state` 파라미터 검증 및 PKCE 처리를 위해 Spring Security는 인가 요청 시 생성한 `OAuth2AuthorizationRequest` 객체를 콜백 시점까지 보존해야 합니다. 세션이 없으면 이 객체를 찾을 수 없습니다.

#### 해결

`AuthorizationRequestRepository`를 직접 구현해 인가 요청 객체를 **쿠키**에 직렬화하여 저장합니다.

```java
// CookieAuthorizationRequestRepository.java
public class CookieAuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String COOKIE_NAME = "oauth2_auth_request";
    private static final int COOKIE_EXPIRE_SECONDS = 180;

    @Override
    public void saveAuthorizationRequest(...) {
        // OAuth2AuthorizationRequest를 직렬화 → Base64 → HttpOnly 쿠키로 저장
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(...) {
        // 쿠키에서 값을 꺼내 역직렬화하여 반환
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(...) {
        // 반환 후 쿠키 삭제 (일회성 보장)
    }
}
```

`SecurityConfig`에서 다음과 같이 등록합니다.

```java
.oauth2Login(oauth2 -> oauth2
    .authorizationEndpoint(endpoint -> endpoint
        .authorizationRequestRepository(new CookieAuthorizationRequestRepository())
    )
)
```

#### 주의사항

- 쿠키 만료 시간(`180초`)이 지나면 인가 요청이 만료되어 로그인이 실패합니다. 사용자가 인가 화면에서 너무 오래 대기한 경우에 해당합니다.
- `SerializationUtils`를 사용하므로 `OAuth2AuthorizationRequest`가 `Serializable`해야 합니다. Spring Security 기본 구현체는 이를 보장합니다.
- HTTPS 환경에서는 쿠키에 `Secure` 속성을 반드시 추가해야 합니다.
