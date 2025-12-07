## 두 로컬 트랜잭션을 한 번에 묶으려다 발생하는 부분 커밋 재현

### 맥락
- 하나의 서비스 메서드에서 두 MySQL 로컬 트랜잭션(DB1: 승인처리, DB2: 승인통합)을 동시에 제어하려다, 한쪽 커밋은 성공하고 다른 쪽 커밋/응답이 실패해 휴리스틱한(부분 커밋) 상태가 발생했던 실무 사고를 재현한다.
- 당시엔 외부 부하 때문에 DB 응답이 지연·타임아웃 되었고, 스프링 AOP 트랜잭션에서 `HeuristicCompletionException`이 터졌다.
- 이 샘플은 “코드에서 강제 예외” 대신 **DB2의 자원/타임아웃을 낮추고 k6 부하로 실제 지연을 유발**해 휴리스틱 예외를 체감하도록 만든다.

### 구현 핵심
- 서비스 스테레오타입(@Service) 첫 호출에만 AOP가 개입해 `CompositeTransactionManager`로 다중 로컬 트랜잭션을 한 번에 열고 커밋/롤백한다(커넥션 직접 제어 없음).
- DB2는 Docker 레벨에서 CPU/메모리를 제한하고, init 스크립트로 타임아웃을 극단적으로 낮췄다 (`innodb_lock_wait_timeout=2`, `innodb_rollback_on_timeout=ON`, `net_read/write_timeout=2`).
- 커밋 도중 DB2가 타임아웃/지연으로 실패하면 `HeuristicCompletionException(STATE_MIXED)`을 발생시켜 “DB1은 반영, DB2는 실패” 상태를 드러낸다.

### 흐름
1) `/approve` 호출 → `ApprovalService.approveAndSendUms()` 실행  
2) AOP가 DB1·DB2 트랜잭션 시작(auto-commit 해제, 커넥션 바인딩)  
3) DB1: 승인 내역 insert, DB2: UMS 내역 insert  
4) DB1 커밋 성공  
5) DB2 커밋 시 부하/타임아웃으로 실패하면 `HeuristicCompletionException` 발생 → DB2 롤백, DB1 데이터만 남음

### 가장 빠른 재현(한두 커맨드)
1) DB+앱 기동(빌드 포함):  
   ```bash
   cd heuristic-exception
   docker compose up --build -d app
   ```
   - DB2는 CPU 0.25, 메모리 256MB로 제한된 상태로 올라온다.
2) 부하 주입으로 실패 지점 관측:  
   ```bash
   docker compose --profile stress run --rm k6
   ```
   - 기본 40 VU, 60초. 조정 예시: `VUS=80 DURATION=120s docker compose --profile stress run --rm k6`
3) 결과 카운트 확인(부분 커밋 여부 바로 보기):  
   ```bash
   ./scripts/show-status.sh
   ```
4) 휴리스틱 예외 발생 여부 확인:  
   - 앱 로그 검색: `docker compose logs app | grep -i HeuristicCompletionException`  
   - 단건 호출 상태 확인: `./scripts/call-once.sh` (HTTP 500이면 로그에서 HeuristicCompletionException 메시지를 함께 확인)

### 단건 호출 확인 (`src/main/resources/approve-test.http`)
```http
POST http://localhost:8080/approve
Content-Type: application/json

{
  "approvalId": "APP-1",
  "amount": 100.00,
  "phoneNumber": "010-1234-5678",
  "message": "hello"
}
```

### 기대 관측
- 부하 없이 호출 시: 보통 두 DB 모두 커밋(또는 DB2 타임아웃에 따라 실패)  
- 부하 + 제한된 DB2 자원 환경에서: DB2 커밋이 타임아웃/락 대기 실패 → 스프링이 `HeuristicCompletionException`을 던지고 DB1 데이터만 남는다.
- 확률적 재현이므로, 실패 시점은 부하·호스트 리소스에 따라 다르다.

### 테스트
```bash
./gradlew :heuristic-exception:test
```
- `HeuristicExceptionIntegrationTest`는 예외가 발생하면 `HeuristicCompletionException` 루트원인을, 발생하지 않아도 DB2 반영이 0~1 사이인지 확인한다.  
- 컨테이너 기동 및 DB 권한 반영 후 실행해야 한다. Docker로 앱을 올렸다면 로컬에서도 동일 Gradle 테스트 실행 가능하다.

### 실제 환경과의 차이, 이렇게 재현하는 이유
- 실제 사고는 동일 DB를 다른 팀이 폭주시켜 락 대기/스토리지 지연이 커졌고, 커밋 응답이 늦어져 휴리스틱 예외가 발생했다. 이 샘플은 이를 **DB2 자원 제한 + 짧은 타임아웃 + k6 부하** 조합으로 근사한다.
- 호스트 자원·가상화 환경마다 임계점이 다르므로 “100% 동일”하지는 않다. 대신 재현 확률을 높이는 설정을 제공하고, 실패가 발생하면 `HeuristicCompletionException`으로 확인할 수 있다.
- 코드 레벨 강제 실패는 제거했으며, 인프라/DB 레벨 지연으로만 부분 커밋을 노린다.
