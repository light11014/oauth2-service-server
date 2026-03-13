# Client App (service-demo)

Spring Boot 기반 OAuth2 Client + Resource Server.  
Auth Server(9000)에서 토큰을 받아 React(3000)와 통신한다.

---

## 프로젝트 구조

```
src/main/
├── java/dev/oauth/
│   ├── config/
│   │   └── SecurityConfig.java       # OAuth2 Client + Resource Server 설정
│   └── controller/
│       └── UserController.java       # /api/me 엔드포인트
└── resources/
    └── application.yml               # OAuth2 클라이언트 등록 정보
```

---

## 의존성

```gradle
implementation 'org.springframework.boot:spring-boot-starter-web'
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
```

---

## 설정 파일

### application.yml

```yaml
spring:
  application:
    name: service-demo
  security:
    oauth2:
      client:
        registration:
          service-demo:
            client-id: oidc-client           # Auth Server에 등록된 client-id
            client-secret: secret            # Auth Server에 등록된 secret
            authorization-grant-type: authorization_code
            redirect-uri: "http://127.0.0.1:8080/login/oauth2/code/oidc-client"
            scope: openid, profile
        provider:
          service-demo:
            authorization-uri: http://localhost:9000/oauth2/authorize
            token-uri: http://localhost:9000/oauth2/token
            user-info-uri: http://localhost:9000/userinfo
            jwk-set-uri: http://localhost:9000/oauth2/jwks
            user-name-attribute: sub
```

---

## 엔드포인트

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/oauth2/authorization/service-demo` | OAuth2 로그인 시작 (Spring 자동 생성) | 불필요 |
| GET | `/login/oauth2/code/oidc-client` | Auth Server 콜백 (Spring 자동 생성) | 불필요 |
| GET | `/api/me` | 로그인한 유저 정보 반환 | 필요 (쿠키) |
| POST | `/api/auth/logout` | 로그아웃 + 쿠키 삭제 | 필요 |

---

## SecurityConfig 주요 설정

### CORS
```
허용 Origin : http://localhost:3000 (React)
허용 Method : *
자격증명    : true (쿠키 전송 허용)
```

### OAuth2 Client
```
로그인 시작  : /oauth2/authorization/service-demo
콜백 처리   : /login/oauth2/code/oidc-client
로그인 성공 후:
  1. Access Token → HttpOnly 쿠키(access_token)에 저장
  2. http://localhost:3000/home 으로 리다이렉트
```

### OAuth2 Resource Server
```
JWT 검증 방식 : JWK (http://localhost:9000/oauth2/jwks)
토큰 위치    : Authorization 헤더 대신 쿠키(access_token)에서 꺼냄
```

### 쿠키 설정
```
이름     : access_token
HttpOnly : true   (JS 접근 불가 - XSS 방어)
Secure   : false  (운영환경에서는 true, HTTPS 필요)
Path     : /
MaxAge   : 3600   (1시간)
```

---

## 로그인 흐름

```
1. React → http://localhost:8080/oauth2/authorization/service-demo
2. Auth Server(9000) 로그인 페이지로 리다이렉트
3. 유저 로그인
4. Auth Server → http://127.0.0.1:8080/login/oauth2/code/oidc-client?code=xxx
5. Spring이 code → Access Token 교환 (자동)
6. successHandler: Access Token을 HttpOnly 쿠키에 저장
7. http://localhost:3000/home 으로 리다이렉트
8. 이후 API 요청 시 쿠키 자동 전송 → Resource Server가 JWT 검증
```

---

## API 명세

### GET /api/me

로그인한 유저 정보를 반환한다.

**Request**
```
Cookie: access_token={jwt}
```

**Response**
```json
{
  "sub": "user123",
  "name": "홍길동"
}
```

**Error**
```
401 Unauthorized - 쿠키 없거나 토큰 만료
```

---

## Apidog 테스트 방법

```
1. 브라우저에서 로그인 흐름 타기
   http://localhost:8080/oauth2/authorization/service-demo

2. 브라우저 개발자도구 → Application → Cookies
   access_token 값 복사

3. Apidog 요청 헤더에 추가
   Cookie: access_token={복사한값}

4. GET http://localhost:8080/api/me 요청
```

---

## Auth Server와 맞춰야 할 값

| 항목 | Client App | Auth Server |
|------|-----------|-------------|
| client-id | `oidc-client` | `RegisteredClient.clientId` |
| client-secret | `secret` | `RegisteredClient.clientSecret` ({noop} 제거) |
| redirect-uri | `http://127.0.0.1:8080/login/oauth2/code/oidc-client` | `RegisteredClient.redirectUri` |
| scope | `openid, profile` | `RegisteredClient.scope` |

---

## 포트 정보

| 서버 | 포트 |
|------|------|
| Auth Server | 9000 |
| Client App (Spring) | 8080 |
| React | 3000 |