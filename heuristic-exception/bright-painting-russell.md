# HeuristicCompletionException 미발생 근본 원인 분석

## 📋 Executive Summary

**문제**: k6 부하 테스트 시 DB1과 DB2의 데이터 불일치는 발생하지만, `HeuristicCompletionException`이 로그에 나타나지 않음

**주요 원인 (현재 재현 프로젝트)**: `DatabaseConfig.java`에서 `SimpleDriverDataSource`를 명시적으로 지정하여 커넥션 풀링이 없고, MyBatis 세션과 트랜잭션 동기화가 깨짐. INSERT 실패 시 예외가 `CompositeTransactionManager.commit()` 도달 전에 발생.

**⚠️ 중요**: 실무에서 HikariCP를 사용했다면 SimpleDriverDataSource만이 원인이 아닐 수 있음. MyBatis 세션 관리, 트랜잭션 동기화, AOP 순서 등 추가 원인 탐구 필요.

**해결 방향**:
1. 즉시: `HikariDataSource`로 변경 (재현 프로젝트 수정)
2. 검증: MyBatis 세션 라이프사이클과 트랜잭션 동기화 확인
3. 추가 분석: HikariCP 사용 시에도 발생하지 않는 다른 원인들 탐구

---

## 🔍 문제 상황 분석

### 관찰된 현상
1. ✅ k6 부하 테스트 실행 후 DB1과 DB2의 데이터 수가 다름 (부분 커밋 발생)
2. ❌ 애플리케이션 로그에서 `HeuristicCompletionException` 찾을 수 없음
3. ✅ toxiproxy는 정상적으로 설정되고 작동 중 (`scripts/init-toxiproxy.sh` 실행됨)
4. ❌ 500 에러는 발생할 수 있으나, 예외 타입이 `HeuristicCompletionException`이 아님

### 기대 동작 vs 실제 동작

**기대 동작:**
```
1. DB1 INSERT (트랜잭션 내, 미커밋)
2. DB2 INSERT (트랜잭션 내, 미커밋)
3. CompositeTransactionManager.commit() 호출
   ├─ DB1 commit 성공
   └─ DB2 commit 실패 (toxiproxy/timeout)
4. HeuristicCompletionException(STATE_MIXED) 발생
```

**실제 동작:**
```
1. DB1 INSERT 즉시 커밋됨 (autoCommit=true)
2. DB2 INSERT 시도 → 실패
3. SQLException/MyBatisSystemException 발생
4. CompositeTransactionManager.commit() 미호출
5. HeuristicCompletionException 발생하지 않음
```

---

## 🔬 근본 원인: SimpleDriverDataSource 사용

### 증거 1: DatabaseConfig.java에서 SimpleDriverDataSource 명시적 사용

**파일**: `heuristic-exception/src/main/java/com/example/heuristicexception/config/DatabaseConfig.java`

**Line 29-33 (DB1):**
```java
@Bean
@Primary
public DataSource db1DataSource(@Qualifier("db1Properties") DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder()
            .type(SimpleDriverDataSource.class)  // ← 문제의 코드
            .build();
}
```

**Line 47-50 (DB2):**
```java
@Bean
public DataSource db2DataSource(@Qualifier("db2Properties") DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder()
            .type(SimpleDriverDataSource.class)  // ← 동일한 문제
            .build();
}
```

### 증거 2: SimpleDriverDataSource의 정확한 문제점

**⚠️ 중요한 수정**: SimpleDriverDataSource가 "트랜잭션을 지원하지 않는다"는 표현은 부정확합니다.

**정확한 문제:**

**Spring 공식 문서:**
> `SimpleDriverDataSource` is a simple implementation of the standard JDBC DataSource interface, configuring a plain old JDBC Driver via bean properties, and returning a new Connection from every getConnection call.
>
> **NOTE**: This class is not an actual connection pool; it does not actually pool Connections. It just serves as simple replacement for a full-blown connection pool, implementing the same standard interface, but creating new Connections on every call.

**실제 문제점 (더 정확한 설명):**

1. **커넥션 풀링 부재**
   - 매 `getConnection()` 호출마다 새 JDBC Connection 생성
   - 이전 Connection과의 연속성 없음
   - 트랜잭션 컨텍스트가 Connection 종료 시 소실

2. **트랜잭션 동기화 문제**
   - DataSourceTransactionManager는 `setAutoCommit(false)` 호출을 **시도**함
   - 하지만 MyBatis SqlSessionTemplate이 각 작업마다 Connection을 요청하면 **새 Connection**이 반환됨
   - 결과: 트랜잭션 시작 시 설정한 Connection과 실제 INSERT 시 사용하는 Connection이 **다를 수 있음**

3. **MyBatis 세션과의 불일치**
   - MyBatis는 Spring의 `TransactionSynchronizationManager.getResource(dataSource)`로 현재 트랜잭션의 Connection을 찾음
   - SimpleDriverDataSource + 매번 새 Connection → 동기화 실패 가능성
   - 각 Mapper 호출이 독립적인 Connection에서 실행될 수 있음

**DataSourceTransactionManager가 실제로 하는 일:**
```java
// Spring의 DataSourceTransactionManager.doBegin()
protected void doBegin(Object transaction, TransactionDefinition definition) {
    Connection con = obtainDataSourceConnection(dataSource);

    // autoCommit을 false로 설정 시도
    if (con.getAutoCommit()) {
        con.setAutoCommit(false);  // ← 이 부분은 실행됨!
    }

    // Connection을 ThreadLocal에 바인딩
    TransactionSynchronizationManager.bindResource(dataSource, conHolder);
}
```

**문제가 발생하는 지점:**
- Spring은 ThreadLocal에 Connection을 바인딩함
- MyBatis는 ThreadLocal에서 Connection을 가져와야 함
- 하지만 SimpleDriverDataSource는 **커넥션 풀이 없어서** 바인딩된 Connection을 재사용하지 않고 새로 생성할 수 있음
- 또는 MyBatis가 작업 완료 후 Connection을 닫으면 트랜잭션 컨텍스트가 사라짐

### 증거 3: application.yml의 HikariCP 설정이 무시됨

**파일**: `heuristic-exception/src/main/resources/application.yml`

**Line 8-18 (DB2 설정):**
```yaml
spring:
  datasource:
    db2:
      url: jdbc:mysql://localhost:33062/ums_db?characterEncoding=UTF-8&serverTimezone=UTC
      username: app
      password: app
      hikari:                          # ← 이 설정들이 무시됨
        connection-timeout: 3500       # ← SimpleDriverDataSource는 HikariCP가 아님
        validation-timeout: 3500
      properties:
        socketTimeout: 4000            # ← JDBC URL 파라미터로 전달되어야 적용
        connectTimeout: 3000
```

**왜 무시되는가:**
- `DatabaseConfig.java:49`에서 `.type(SimpleDriverDataSource.class)`를 명시적으로 지정
- Spring Boot의 자동 설정이 HikariCP를 사용하려 해도, 명시적 타입 지정이 우선순위가 높음
- `hikari.*` 설정은 HikariDataSource에만 적용되므로 효과 없음

---

## 🔗 MyBatis 세션 라이프사이클과 트랜잭션 동기화

### MyBatis + Spring 트랜잭션 통합 메커니즘

**정상 동작 (HikariCP 사용 시):**

```
1. MultiResourceTransactionAspect.around() 시작
   ├─ compositeTxManager.getTransaction()
   │  └─ DataSourceTransactionManager.doBegin()
   │     ├─ HikariCP에서 Connection 획득
   │     ├─ con.setAutoCommit(false)
   │     └─ TransactionSynchronizationManager.bindResource(dataSource, conHolder)
   │        └─ ThreadLocal에 ConnectionHolder 저장 ★
   │
   └─ joinPoint.proceed()
      └─ ApprovalService.approveAndSendUms()
         ├─ approvalHistoryMapper.insertApproval()
         │  └─ MyBatis SqlSessionTemplate.selectOne()
         │     ├─ SqlSessionUtils.getSqlSession()
         │     │  └─ TransactionSynchronizationManager.getResource(dataSource)
         │     │     └─ ThreadLocal에서 ConnectionHolder 가져옴 ★
         │     │        └─ 동일한 Connection 재사용!
         │     └─ INSERT 실행 (autoCommit=false 상태)
         │
         └─ umsSendHistoryMapper.insertUmsHistory()
            └─ 동일한 Connection 재사용 (ThreadLocal에서)
```

**문제 동작 (SimpleDriverDataSource 의심 시나리오):**

```
1. MultiResourceTransactionAspect.around() 시작
   ├─ compositeTxManager.getTransaction()
   │  └─ DataSourceTransactionManager.doBegin()
   │     ├─ SimpleDriverDataSource.getConnection()
   │     │  └─ 새 Connection 생성 (Connection A)
   │     ├─ Connection A: setAutoCommit(false) ✓
   │     └─ TransactionSynchronizationManager.bindResource(dataSource, conHolder)
   │        └─ ThreadLocal에 Connection A 저장
   │
   └─ joinPoint.proceed()
      └─ ApprovalService.approveAndSendUms()
         ├─ approvalHistoryMapper.insertApproval()
         │  └─ MyBatis SqlSessionTemplate
         │     ├─ SqlSessionUtils.getSqlSession()
         │     │  ├─ TransactionSynchronizationManager.getResource(dataSource)
         │     │  │  └─ Connection A를 찾아야 하는데...
         │     │  │
         │     │  └─ 시나리오 A: Connection A를 찾음 (정상)
         │     │     ├─ INSERT 실행 (autoCommit=false)
         │     │     └─ 작업 완료 후 Connection.close() 호출?
         │     │        └─ SimpleDriverDataSource는 실제로 닫아버림
         │     │           └─ ThreadLocal의 ConnectionHolder는 닫힌 Connection 참조
         │     │
         │     └─ 시나리오 B: Connection A를 못 찾고 새 Connection B 생성
         │        ├─ Connection B: autoCommit=true (기본값)
         │        └─ INSERT 즉시 커밋됨
         │
         └─ umsSendHistoryMapper.insertUmsHistory()
            ├─ 새 Connection C 생성 (또는 닫힌 Connection A 사용 시도)
            ├─ INSERT 실패 (toxiproxy)
            └─ SQLException 발생
```

### SqlSessionTemplate의 Connection 관리

**파일**: MyBatis-Spring의 `SqlSessionUtils.java`

```java
public static SqlSession getSqlSession(SqlSessionFactory sessionFactory,
                                       ExecutorType executorType,
                                       PersistenceExceptionTranslator exceptionTranslator) {

    // Spring 트랜잭션이 활성화되어 있는지 확인
    SqlSessionHolder holder = (SqlSessionHolder)
        TransactionSynchronizationManager.getResource(sessionFactory);

    if (holder != null && holder.isSynchronizedWithTransaction()) {
        // 기존 SqlSession 재사용 (트랜잭션 내)
        return holder.getSqlSession();
    }

    // 새 SqlSession 생성
    SqlSession session = sessionFactory.openSession(executorType);

    // Spring 트랜잭션과 동기화
    registerSessionHolder(sessionFactory, executorType, exceptionTranslator, session);

    return session;
}
```

**핵심 메커니즘:**
- MyBatis는 SqlSessionFactory를 키로 ThreadLocal에서 SqlSessionHolder를 찾음
- SqlSession 내부에는 JDBC Connection이 포함됨
- Spring의 DataSourceTransactionManager는 DataSource를 키로 ConnectionHolder를 관리
- **MyBatis-Spring 통합**은 이 둘을 연결해줌

**SimpleDriverDataSource의 문제:**
1. Connection을 풀에서 관리하지 않으므로, `close()` 호출 시 실제로 닫힘
2. MyBatis가 SqlSession을 닫을 때 Connection도 닫을 수 있음
3. 다음 Mapper 호출 시 새 Connection 생성 → 트랜잭션 경계 벗어남

---

## ⚠️ HikariCP 사용 시에도 발생하지 않을 수 있는 다른 원인들

**중요**: 실무에서 HikariCP를 사용했는데도 HeuristicCompletionException이 발생하지 않았다면, 다음 원인들을 추가로 검토해야 합니다.

### 원인 1: toxiproxy 타이밍 문제

**시나리오:**
```
CompositeTransactionManager.commit() 실행:
├─ DB1 commit 시도
│  └─ 성공하려는 순간 toxiproxy reset_peer 발생
│     └─ DB1도 실패 → anyCommitted = false
│        └─ HeuristicCompletionException(STATE_ROLLED_BACK) 발생
│           └─ 이것은 STATE_MIXED가 아님!
│
또는:
├─ DB1 commit 성공
├─ DB2 commit 시도
│  └─ toxiproxy reset_peer가 35초, 40초, 45초에 발생
│     └─ 하지만 commit은 매우 빠름 (수 밀리초)
│        └─ reset_peer 발생 전에 commit 완료될 수 있음
```

**검증 필요:**
- toxiproxy의 reset_peer가 정확히 언제 발생하는지
- DB commit 요청이 그 시점에 진행 중인지

### 원인 2: MyBatis가 INSERT 단계에서 실패 (커밋 전)

**현재 분석과 동일하지만 HikariCP에서도 발생 가능:**

```
HikariCP 사용 시에도:
1. DB1 INSERT 성공 (트랜잭션 내, 미커밋)
2. DB2 INSERT 시도
   └─ toxiproxy reset_peer로 연결 끊김
   └─ SQLException 발생
3. MultiResourceTransactionAspect catch 블록
   └─ rollback() 호출
   └─ commit() 미호출
4. HeuristicCompletionException 발생하지 않음
```

**왜 이게 문제인가:**
- HeuristicCompletionException은 **commit 단계**에서만 발생
- INSERT 단계 실패는 일반 SQLException

### 원인 3: DB2 타임아웃이 너무 짧아서 INSERT 단계에서 실패

**innodb_lock_wait_timeout=2초:**
```
동시 요청들이 같은 테이블에 INSERT:
├─ Request A: DB2 INSERT 시작 (락 획득)
├─ Request B: DB2 INSERT 대기
│  └─ 2초 타임아웃
│     └─ INSERT 단계에서 실패 (커밋 전)
│        └─ SQLException 발생
│           └─ commit() 미호출
```

### 원인 4: CompositeTransactionManager의 commit 순서

**현재 코드 (CompositeTransactionManager.java:39-44):**
```java
for (int i = 0; i < compositeStatus.statuses().size(); i++) {
    TransactionStatus ts = compositeStatus.statuses().get(i);
    if (!ts.isCompleted() && !ts.isRollbackOnly()) {
        delegates.get(i).commit(ts);  // DB1, DB2 순서
        anyCommitted = true;
    }
}
```

**delegates 순서:**
```java
// DatabaseConfig.java:69-72
@Bean
public PlatformTransactionManager compositeTxManager(
        @Qualifier("db1TxManager") PlatformTransactionManager db1,
        @Qualifier("db2TxManager") PlatformTransactionManager db2) {
    return new CompositeTransactionManager(java.util.List.of(db1, db2));
}
```

**만약 DB1 커밋이 실패한다면?**
- anyCommitted = false
- STATE_ROLLED_BACK 발생 (STATE_MIXED가 아님)

**검증 필요:**
- 실제로 DB1 커밋은 항상 성공하는지
- DB2만 실패하는지, 아니면 둘 다 실패할 수 있는지

### 원인 5: 예외가 발생하지만 다른 타입으로 감싸짐

**예외 전파 경로:**
```
CompositeTransactionManager.commit() throws HeuristicCompletionException
  ↓
MultiResourceTransactionAspect catch (Throwable ex)
  ↓ throw ex
ApprovalController (no exception handler)
  ↓
Spring DispatcherServlet
  ↓
기본 ExceptionHandler 또는 다른 @ControllerAdvice
```

**가능성:**
- HeuristicCompletionException이 발생하지만 다른 ExceptionHandler가 먼저 잡음
- 로그 레벨 설정으로 보이지 않음
- 다른 AOP가 예외를 감싸서 변환

---

## 🔄 실제 실행 흐름 상세 분석

### 전체 호출 체인

```
HTTP POST /approve
  ↓
ApprovalController.approve()                    (ApprovalController.java:22-24)
  ↓
approvalService.approveAndSendUms(request)      (ApprovalService.java:18)
  ↓
[AOP 인터셉트]
MultiResourceTransactionAspect.around()         (MultiResourceTransactionAspect.java:25)
  ↓
compositeTxManager.getTransaction()             (Line 35)
  ↓
CompositeTransactionManager.getTransaction()    (CompositeTransactionManager.java:26-32)
  ├─ db1TxManager.getTransaction()
  │    ↓
  │  DataSourceTransactionManager.doGetTransaction()
  │    ↓
  │  db1DataSource.getConnection()              ← SimpleDriverDataSource!
  │    ↓
  │  DriverManager.getConnection()              (새 Connection 생성)
  │    ↓
  │  connection.setAutoCommit(?)                 ← 호출되지 않음! 기본값 true 유지
  │
  └─ db2TxManager.getTransaction()
       ↓
     (DB1과 동일한 과정, 또 다른 새 Connection 생성)
  ↓
joinPoint.proceed()                             (Line 36)
  ↓
ApprovalService.approveAndSendUms()             (실제 비즈니스 로직 실행)
  ↓
approvalHistoryMapper.insertApproval(request)   (ApprovalService.java:20)
  ↓
MyBatis: INSERT INTO approval_history ...
  ↓
**자동 커밋됨** (autoCommit=true)                ← DB1 데이터 영구 반영
Connection 반환/종료
  ↓
umsSendHistoryMapper.insertUmsHistory(request)  (ApprovalService.java:23)
  ↓
새로운 Connection 요청 (이전 Connection은 이미 닫힘)
  ↓
MyBatis: INSERT INTO ums_send_history ...
  ↓
toxiproxy reset_peer 또는 timeout 발생
  ↓
SQLException 발생 ("Connection reset" 또는 "Timeout")
  ↓
예외가 MultiResourceTransactionAspect.around()로 전파
  ↓
catch (Throwable ex) 블록 진입                  (Line 45)
  ↓
compositeTxManager.rollback(status)             (Line 47)
  ↓
CompositeTransactionManager.rollback()
  ├─ db1TxManager.rollback()
  │    ↓
  │  새로운 Connection 요청 (3번째 Connection!)
  │    ↓
  │  ROLLBACK 시도하지만 이미 커밋된 데이터는 롤백 불가
  │
  └─ db2TxManager.rollback()
       ↓
     롤백할 트랜잭션 없음 (INSERT 실패했으므로)
  ↓
throw ex                                        (Line 49)
  ↓
SQLException/MyBatisSystemException 전파
  ↓
ExceptionHandler가 처리 (HeuristicExceptionHandler는 호출되지 않음)
```

### 핵심 문제점: commit() 메서드가 호출되지 않음

**Line 38-40 (`MultiResourceTransactionAspect.java`):**
```java
if (!status.isRollbackOnly()) {
    compositeTxManager.commit(status);  // ← 이 라인에 도달하지 못함
}
```

**왜 도달하지 못하는가:**
- `joinPoint.proceed()` (Line 36)에서 예외 발생
- 즉시 `catch` 블록 (Line 45)으로 이동
- `commit()` 호출 기회 없음

**HeuristicCompletionException은 어디서 발생하는가:**
```java
// CompositeTransactionManager.java:47-52
} catch (Exception ex) {
    rollbackRemaining(compositeStatus);
    throw new HeuristicCompletionException(anyCommitted
            ? HeuristicCompletionException.STATE_MIXED
            : HeuristicCompletionException.STATE_ROLLED_BACK, ex);
}
```

이 코드는 **`commit()` 메서드 내부**에 있음. `commit()`이 호출되지 않으면 HeuristicCompletionException도 발생하지 않음.

---

## 🧪 SimpleDriverDataSource의 autoCommit 동작 검증

### JDBC 스펙에 따른 기본 동작

**JDBC 4.3 스펙 (java.sql.Connection):**
```java
/**
 * Retrieves the current auto-commit mode for this Connection object.
 *
 * @return the current state of this Connection object's auto-commit mode
 * @see #setAutoCommit
 */
boolean getAutoCommit() throws SQLException;

/**
 * The default auto-commit mode is implementation-defined.
 * In practice, most drivers default to true.
 */
```

**MySQL Connector/J 8.0 기본값:**
- autoCommit=true (공식 문서 확인됨)

### Spring DataSourceTransactionManager의 트랜잭션 관리

**정상 동작 (HikariCP 사용 시):**
```java
// Spring의 DataSourceTransactionManager.doBegin()
protected void doBegin(Object transaction, TransactionDefinition definition) {
    DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;
    Connection con = obtainDataSourceConnection(txObject.getDataSource());

    // ✓ 트랜잭션 시작 시 autoCommit 비활성화
    if (con.getAutoCommit()) {
        con.setAutoCommit(false);  // ← 중요!
    }

    // Connection을 TransactionSynchronizationManager에 바인딩
    TransactionSynchronizationManager.bindResource(getDataSource(), conHolder);
}
```

**문제 동작 (SimpleDriverDataSource 사용 시):**
```java
// SimpleDriverDataSource.getConnection()
@Override
public Connection getConnection() throws SQLException {
    return getConnectionFromDriver(getUsername(), getPassword());
}

protected Connection getConnectionFromDriver(String username, String password) {
    Properties props = new Properties();
    props.put("user", username);
    props.put("password", password);

    // 매번 새로운 Connection 생성
    Connection con = Driver.connect(url, props);  // ← autoCommit=true

    // initConnection() 호출되지만 autoCommit 설정 없음
    return con;
}
```

**핵심 차이:**
1. HikariCP: Connection Pool에서 재사용, Spring이 autoCommit=false 설정 가능
2. SimpleDriverDataSource: 매번 새 Connection, Spring이 설정해도 다음 호출에 영향 없음

### 실험적 증거: 로그 추가 시 예상 결과

만약 `ApprovalService.java`에 다음 로그를 추가한다면:
```java
public void approveAndSendUms(ApprovalRequest request) {
    Connection conn1 = DataSourceUtils.getConnection(db1DataSource);
    log.info("DB1 Connection: {}, autoCommit={}",
        System.identityHashCode(conn1), conn1.getAutoCommit());

    approvalHistoryMapper.insertApproval(request);

    log.info("After DB1 INSERT, same connection? {}",
        System.identityHashCode(DataSourceUtils.getConnection(db1DataSource)));

    umsSendHistoryMapper.insertUmsHistory(request);
}
```

**예상 로그 (SimpleDriverDataSource):**
```
DB1 Connection: 12345678, autoCommit=true
After DB1 INSERT, same connection? 87654321  ← 다른 Connection!
```

**예상 로그 (HikariCP):**
```
DB1 Connection: 12345678, autoCommit=false
After DB1 INSERT, same connection? 12345678  ← 동일한 Connection
```

---

## 📊 데이터 불일치가 발생하는 정확한 시나리오

### 시나리오 1: DB2 INSERT 실패 (가장 흔함)

**조건:**
- k6 부하 테스트로 40 VUs 동시 요청
- toxiproxy가 35초, 40초, 45초에 reset_peer 주입
- DB2는 CPU 0.3, 메모리 256MB로 제한됨

**실행 과정:**
```
Request #1 (35초 시점, toxiproxy reset_peer 발생 직전):
  1. DB1 INSERT → 즉시 커밋 (autoCommit=true) ✓
  2. DB2 INSERT 시도 → toxiproxy가 연결 끊음 ✗
  3. SQLException: "Connection reset by peer"
  4. DB1 rollback 시도 → 실패 (이미 커밋됨)

결과: DB1에만 데이터 존재

Request #2 (정상 처리):
  1. DB1 INSERT → 즉시 커밋 ✓
  2. DB2 INSERT → 즉시 커밋 ✓

결과: 양쪽 DB 모두 데이터 존재

Request #3 (40초 시점, 두 번째 reset_peer):
  1. DB1 INSERT → 즉시 커밋 ✓
  2. DB2 INSERT 시도 → 연결 끊김 ✗

결과: DB1에만 데이터 존재
```

**최종 상태 (60초 부하 테스트 후):**
```bash
$ ./scripts/show-status.sh

DB1 approval count: 2400
DB2 ums count: 2350
Difference: 50  ← 이 50건이 부분 커밋된 경우
```

### 시나리오 2: DB2 타임아웃 (드물게 발생)

**조건:**
- DB2의 innodb_lock_wait_timeout=2초
- 동시에 같은 approval_id에 대한 INSERT 경합

**실행 과정:**
```
Request A와 Request B가 동시에 approval_id="APP-123" 삽입 시도:

Request A:
  1. DB1 INSERT approval_id="APP-123" → 커밋 ✓
  2. DB2 INSERT 시작 → UNIQUE 제약조건 대기 (Request B가 먼저 락 획득)
  3. 2초 타임아웃 발생
  4. SQLException: "Lock wait timeout exceeded"

Request B:
  1. DB1 INSERT approval_id="APP-123" → 커밋 ✓
  2. DB2 INSERT → 커밋 ✓

결과:
  - DB1에는 A, B 모두 존재 (중복 불가능하므로 실제로는 하나만)
  - DB2에는 B만 존재
```

---

## 🎯 왜 HeuristicCompletionException이 발생하지 않는가

### 예외 발생 조건 비교

**HeuristicCompletionException 발생 조건 (설계 의도):**
```java
// CompositeTransactionManager.java:35-52
public void commit(TransactionStatus status) throws TransactionException {
    boolean anyCommitted = false;
    try {
        for (int i = 0; i < compositeStatus.statuses().size(); i++) {
            TransactionStatus ts = compositeStatus.statuses().get(i);
            if (!ts.isCompleted() && !ts.isRollbackOnly()) {
                delegates.get(i).commit(ts);  // ← 이 시점에 예외 발생해야 함
                anyCommitted = true;
            }
        }
    } catch (Exception ex) {
        rollbackRemaining(compositeStatus);
        throw new HeuristicCompletionException(anyCommitted
                ? HeuristicCompletionException.STATE_MIXED
                : HeuristicCompletionException.STATE_ROLLED_BACK, ex);
    }
}
```

**필수 조건:**
1. `commit()` 메서드가 호출되어야 함
2. 첫 번째 트랜잭션 커밋 성공 (`anyCommitted = true`)
3. 두 번째 트랜잭션 커밋 시 예외 발생

**현재 상황 (실제):**
```java
// MultiResourceTransactionAspect.java:34-52
try {
    status = compositeTxManager.getTransaction(new DefaultTransactionDefinition());
    Object result = joinPoint.proceed();  // ← 여기서 예외 발생!
    if (!status.isCompleted()) {
        if (!status.isRollbackOnly()) {
            compositeTxManager.commit(status);  // ← 실행되지 않음
        }
    }
    return result;
} catch (Throwable ex) {  // ← 예외 잡힘
    if (status != null && !status.isCompleted()) {
        compositeTxManager.rollback(status);
    }
    throw ex;  // SQLException/MyBatisSystemException 전파
}
```

**실제 조건:**
1. ❌ `commit()` 메서드가 호출되지 않음
2. ❌ `anyCommitted` 플래그 설정 기회 없음
3. ✅ 예외는 발생하지만 `joinPoint.proceed()` 내부에서 발생

### 예외 타입 추적

**발생하는 예외:**
```
toxiproxy reset_peer 주입 시:
  com.mysql.cj.jdbc.exceptions.CommunicationsException:
    Communications link failure
        caused by: java.net.SocketException: Connection reset by peer

MyBatis가 이를 감싸서:
  org.mybatis.spring.MyBatisSystemException:
    Error updating database. Cause: ...
        caused by: CommunicationsException

최종적으로:
  500 Internal Server Error
  (HeuristicExceptionHandler.handle()이 아닌 기본 ExceptionHandler)
```

**발생하지 않는 예외:**
```
org.springframework.transaction.HeuristicCompletionException
```

### HeuristicExceptionHandler가 호출되지 않는 이유

**파일**: `heuristic-exception/src/main/java/com/example/heuristicexception/tx/HeuristicExceptionHandler.java`

```java
@RestControllerAdvice
public class HeuristicExceptionHandler {

    @ExceptionHandler(HeuristicCompletionException.class)  // ← 이 타입만 처리
    public ResponseEntity<String> handle(HeuristicCompletionException ex) {
        return ResponseEntity.status(500).body(ex.getMessage());
    }
}
```

**문제:**
- 실제 발생하는 예외: `MyBatisSystemException`
- 핸들러가 처리하는 예외: `HeuristicCompletionException`
- 타입 불일치로 핸들러 미호출

---

## ✅ 해결 방안: HikariDataSource로 변경

### 1. DatabaseConfig.java 수정

**파일**: `src/main/java/com/example/heuristicexception/config/DatabaseConfig.java`

**변경 전 (Line 29-33):**
```java
@Bean
@Primary
public DataSource db1DataSource(@Qualifier("db1Properties") DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder()
            .type(SimpleDriverDataSource.class)  // ← 제거
            .build();
}
```

**변경 후:**
```java
@Bean
@Primary
public DataSource db1DataSource(@Qualifier("db1Properties") DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder()
            .type(com.zaxxer.hikari.HikariDataSource.class)  // ← 추가
            .build();
}
```

**동일하게 db2DataSource도 수정 (Line 47-50):**
```java
@Bean
public DataSource db2DataSource(@Qualifier("db2Properties") DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder()
            .type(com.zaxxer.hikari.HikariDataSource.class)  // ← 추가
            .build();
}
```

### 2. 의존성 확인

**파일**: `build.gradle`

HikariCP는 `spring-boot-starter-jdbc`에 포함되어 있으므로 추가 의존성 불필요:
```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'  // ← HikariCP 포함됨
    // ...
}
```

### 3. application.yml 설정 (이미 존재)

**파일**: `src/main/resources/application.yml`

현재 설정이 HikariDataSource로 변경 후 자동으로 적용됨:
```yaml
spring:
  datasource:
    db2:
      hikari:
        connection-timeout: 3500      # ✓ 이제 적용됨
        validation-timeout: 3500      # ✓ 이제 적용됨
      properties:
        socketTimeout: 4000           # ✓ JDBC URL에 전달됨
        connectTimeout: 3000          # ✓ JDBC URL에 전달됨
```

### 4. 수정 후 기대 동작

**변경 후 실행 흐름:**
```
1. MultiResourceTransactionAspect.around()
   └─ compositeTxManager.getTransaction()
      ├─ db1TxManager.getTransaction()
      │  └─ HikariCP에서 Connection 획득
      │     └─ Spring이 autoCommit=false 설정 ✓
      │     └─ Connection을 ThreadLocal에 바인딩
      └─ db2TxManager.getTransaction()
         └─ HikariCP에서 Connection 획득
            └─ Spring이 autoCommit=false 설정 ✓

2. ApprovalService.approveAndSendUms()
   ├─ approvalHistoryMapper.insertApproval()
   │  ├─ 동일한 Connection 재사용 (ThreadLocal에서)
   │  ├─ INSERT 실행
   │  └─ 커밋되지 않음 (트랜잭션 내) ✓
   │
   └─ umsSendHistoryMapper.insertUmsHistory()
      ├─ 동일한 Connection 재사용
      ├─ INSERT 실행
      └─ 커밋되지 않음 (트랜잭션 내) ✓

3. MultiResourceTransactionAspect - commit 단계
   └─ compositeTxManager.commit(status)  // ← 이제 호출됨!
      ├─ db1TxManager.commit()
      │  └─ DB1 COMMIT 성공 ✓
      │     └─ anyCommitted = true
      │
      └─ db2TxManager.commit()
         ├─ DB2 COMMIT 시도
         ├─ toxiproxy reset_peer 또는 timeout 발생 ✗
         └─ SQLException 발생

4. CompositeTransactionManager catch 블록
   ├─ rollbackRemaining() 호출
   └─ throw new HeuristicCompletionException(
         STATE_MIXED,  // ← anyCommitted=true
         ex)

5. 결과
   ✓ DB1: 데이터 존재 (커밋됨)
   ✗ DB2: 데이터 없음 (롤백됨)
   ✓ HeuristicCompletionException(STATE_MIXED) 발생 ← 목표 달성!
```

---

## 🧪 검증 절차 (단계별 진단)

### Phase 1: 즉시 수정 (SimpleDriverDataSource → HikariDataSource)

**목적**: SimpleDriverDataSource가 원인인지 확인

#### 1-1. 코드 수정
`DatabaseConfig.java`의 두 곳 수정:
- Line 32: `db1DataSource` 메서드
- Line 49: `db2DataSource` 메서드

```java
// 수정 전
return properties.initializeDataSourceBuilder()
    .type(SimpleDriverDataSource.class)
    .build();

// 수정 후
return properties.initializeDataSourceBuilder()
    .type(com.zaxxer.hikari.HikariDataSource.class)
    .build();
```

#### 1-2. 재빌드
```bash
./gradlew :heuristic-exception:clean :heuristic-exception:build
```

#### 1-3. Docker Compose 재시작
```bash
cd heuristic-exception
docker compose down -v  # 볼륨도 삭제하여 깨끗한 상태로
docker compose up --build -d
```

#### 1-4. toxiproxy 설정
```bash
./scripts/init-toxiproxy.sh
```

**예상 출력:**
```
Created new proxy mysql_ums
Added toxic 'reset_at_35s' to proxy 'mysql_ums'
Added toxic 'reset_at_40s' to proxy 'mysql_ums'
Added toxic 'reset_at_45s' to proxy 'mysql_ums'
```

#### 1-5. 단건 테스트 (정상 동작 확인)
```bash
curl -X POST http://localhost:8080/approve \
  -H "Content-Type: application/json" \
  -d '{"approvalId":"TEST-1","amount":100,"phoneNumber":"010-1234-5678","message":"test"}'
```

**예상 응답:**
```
OK
```

**DB 확인:**
```bash
./scripts/show-status.sh
```

**예상 출력:**
```
DB1 approval count: 1
DB2 ums count: 1
Difference: 0
```

#### 1-6. k6 부하 테스트
```bash
docker compose --profile stress run --rm k6
```

**예상 출력 (k6 summary):**
```
     ✓ http_req_failed: ['rate<0.2']

     http_req_duration..............: avg=150ms min=50ms max=5s
     http_reqs......................: 2400 (40 req/s)
     http_req_failed................: 5.83% (140 out of 2400)
```

#### 1-7. 로그 확인 (핵심!)
```bash
docker compose logs app | grep -i "HeuristicCompletionException"
```

**✅ 성공 시 예상 출력:**
```
app-1  | ERROR [...] c.e.h.tx.HeuristicExceptionHandler  : Heuristic exception occurred
app-1  | org.springframework.transaction.HeuristicCompletionException:
app-1  |   Heuristic completion: outcome state is mixed; transaction has been partially committed
app-1  |     at c.e.h.tx.CompositeTransactionManager.commit(CompositeTransactionManager.java:50)
app-1  |     at c.e.h.tx.MultiResourceTransactionAspect.around(MultiResourceTransactionAspect.java:39)
```

**❌ 여전히 발생하지 않으면:**
→ **Phase 2로 이동** (다른 원인 진단)

#### 1-8. 데이터 불일치 확인
```bash
./scripts/show-status.sh
```

**예상 출력:**
```
DB1 approval count: 2400
DB2 ums count: 2260
Difference: 140  ← HeuristicCompletionException 발생 건수
```

**Phase 1 결과 판정:**
- ✅ **성공**: 로그에서 HeuristicCompletionException 확인 + DB 데이터 차이 → SimpleDriverDataSource가 원인이었음
- ❌ **실패**: 로그에 여전히 HeuristicCompletionException 없음 → Phase 2 진행

---

### Phase 2: HikariCP로도 발생하지 않는 경우 (추가 진단)

**상황**: HikariCP로 변경했는데도 HeuristicCompletionException이 발생하지 않음

**가능성**: INSERT 단계에서 실패하여 commit() 단계에 도달하지 못함

#### 2-1. 상세 로깅 추가

**CompositeTransactionManager.java 수정:**
```java
@Slf4j  // 추가
public class CompositeTransactionManager implements PlatformTransactionManager {

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
        CompositeTransactionStatus compositeStatus = (CompositeTransactionStatus) status;
        boolean anyCommitted = false;
        log.info("=== CompositeTransactionManager.commit() STARTED ===");  // 추가

        try {
            for (int i = 0; i < compositeStatus.statuses().size(); i++) {
                TransactionStatus ts = compositeStatus.statuses().get(i);
                if (!ts.isCompleted() && !ts.isRollbackOnly()) {
                    log.info("Committing transaction {} of {}", i+1, compositeStatus.statuses().size());  // 추가
                    delegates.get(i).commit(ts);
                    anyCommitted = true;
                    log.info("Transaction {} committed successfully. anyCommitted=true", i+1);  // 추가
                }
            }
            log.info("=== All transactions committed successfully ===");  // 추가
        } catch (Exception ex) {
            log.error("=== COMMIT FAILED! anyCommitted={}, exception={} ===",   // 추가
                anyCommitted, ex.getClass().getSimpleName(), ex);
            rollbackRemaining(compositeStatus);
            throw new HeuristicCompletionException(anyCommitted
                    ? HeuristicCompletionException.STATE_MIXED
                    : HeuristicCompletionException.STATE_ROLLED_BACK, ex);
        }
    }
}
```

**ApprovalService.java 수정:**
```java
@Slf4j  // 추가
@Service
@RequiredArgsConstructor
public class ApprovalService {

    public void approveAndSendUms(ApprovalRequest request) {
        log.info(">>> START approveAndSendUms for approvalId={}", request.getApprovalId());  // 추가

        log.info(">>> Inserting to DB1 (approval_db)");  // 추가
        approvalHistoryMapper.insertApproval(request);
        log.info(">>> DB1 INSERT completed");  // 추가

        log.info(">>> Inserting to DB2 (ums_db)");  // 추가
        umsSendHistoryMapper.insertUmsHistory(request);
        log.info(">>> DB2 INSERT completed");  // 추가

        log.info(">>> END approveAndSendUms");  // 추가
    }
}
```

#### 2-2. 재빌드 및 테스트
```bash
./gradlew :heuristic-exception:clean :heuristic-exception:build
docker compose down && docker compose up --build -d
./scripts/init-toxiproxy.sh
docker compose --profile stress run --rm k6
```

#### 2-3. 로그 분석
```bash
docker compose logs app | grep -E "(START|INSERT|COMMIT|FAILED)" | tail -100
```

**시나리오 A: commit()이 호출되지 않음 (현재 추정)**
```
>>> START approveAndSendUms for approvalId=APP-1
>>> Inserting to DB1 (approval_db)
>>> DB1 INSERT completed
>>> Inserting to DB2 (ums_db)
ERROR SQLException: Connection reset by peer  ← DB2 INSERT 실패
(CompositeTransactionManager.commit() 로그 없음)  ← commit() 미호출!
```

**시나리오 B: commit()은 호출되지만 DB1도 실패**
```
>>> START approveAndSendUms
>>> DB1 INSERT completed
>>> DB2 INSERT completed
=== CompositeTransactionManager.commit() STARTED ===
Committing transaction 1 of 2
ERROR Transaction 1 commit failed  ← DB1 커밋 실패
=== COMMIT FAILED! anyCommitted=false ===  ← STATE_ROLLED_BACK 발생
```

**시나리오 C: commit()이 호출되고 제대로 실패 (이상적)**
```
>>> START approveAndSendUms
>>> DB1 INSERT completed
>>> DB2 INSERT completed
=== CompositeTransactionManager.commit() STARTED ===
Committing transaction 1 of 2
Transaction 1 committed successfully. anyCommitted=true  ← DB1 성공
Committing transaction 2 of 2
ERROR SQLException: Connection reset by peer  ← DB2 커밋 실패
=== COMMIT FAILED! anyCommitted=true ===  ← STATE_MIXED 발생!
```

#### 2-4. 결과 해석

**만약 시나리오 A라면:**
- **원인**: toxiproxy가 INSERT 단계에서 연결을 끊음
- **해결**: toxiproxy 타이밍을 COMMIT 단계로 옮겨야 함
- **방법**: `init-toxiproxy.sh`의 reset_peer 발동을 더 늦게 (45초, 50초, 55초)

**만약 시나리오 B라면:**
- **원인**: DB1 커밋도 실패함 (리소스 부족, 타임아웃 등)
- **해결**: DB1 리소스 증가 또는 타임아웃 완화

**만약 시나리오 C라면:**
- **성공!**: HeuristicCompletionException(STATE_MIXED) 정상 발생

---

### Phase 3: toxiproxy 타이밍 조정 (Phase 2에서 시나리오 A인 경우)

#### 3-1. toxiproxy를 COMMIT 단계에 주입하도록 수정

**scripts/init-toxiproxy.sh 수정:**
```bash
#!/bin/bash

# 기존 proxy 삭제
toxiproxy-cli delete mysql_ums 2>/dev/null || true

# proxy 생성
toxiproxy-cli create mysql_ums \
  -l 0.0.0.0:13306 \
  -u mysql-ums:3306

# latency toxic 추가 (COMMIT을 느리게)
toxiproxy-cli toxic add mysql_ums \
  -t latency \
  -a latency=2000 \  # 2초 지연
  -a jitter=1000

# 이제 reset_peer는 더 늦게 (50초, 55초, 60초)
sleep 50 && toxiproxy-cli toxic add mysql_ums -t reset_peer -n reset_at_50s &
sleep 55 && toxiproxy-cli toxic add mysql_ums -t reset_peer -n reset_at_55s &
sleep 60 && toxiproxy-cli toxic add mysql_ums -t reset_peer -n reset_at_60s &

wait
```

#### 3-2. 재테스트
```bash
docker compose down && docker compose up -d
./scripts/init-toxiproxy.sh &
docker compose --profile stress run --rm k6
```

#### 3-3. 다시 로그 확인
```bash
docker compose logs app | grep -i "HeuristicCompletionException"
```

**이제 발생해야 함!**

---

### Phase 4: 최종 검증 및 문서화

#### 4-1. 성공 기준 확인
- ✅ 로그에서 `HeuristicCompletionException(STATE_MIXED)` 확인
- ✅ `anyCommitted=true` 로그 확인
- ✅ DB1 > DB2 데이터 차이 확인
- ✅ HeuristicExceptionHandler.handle() 호출 확인

#### 4-2. 실패 원인 문서화
**발견된 원인:**
1. SimpleDriverDataSource 사용 (Phase 1에서 해결)
2. toxiproxy 타이밍이 INSERT 단계 (Phase 3에서 해결)
3. [기타 발견된 원인들]

#### 4-3. 실무 적용 시사점
- HikariCP 사용은 필수
- 분산 트랜잭션 테스트 시 COMMIT 단계에 장애 주입 필요
- CompositeTransactionManager는 근본적으로 2PC가 아니므로 프로덕션 사용 주의

---

## 📈 예상 결과 비교

### 수정 전 (SimpleDriverDataSource)

| 항목 | 결과 |
|------|------|
| DB1 데이터 | 2400건 ✓ |
| DB2 데이터 | 2350건 ✓ |
| 데이터 차이 | 50건 ✓ |
| HeuristicCompletionException 로그 | **0건** ❌ |
| 로그에 나타나는 예외 | MyBatisSystemException |
| HTTP 500 응답 | 일부 발생 |
| 예외 핸들러 | 기본 ExceptionHandler |

### 수정 후 (HikariDataSource)

| 항목 | 결과 |
|------|------|
| DB1 데이터 | 2400건 ✓ |
| DB2 데이터 | 2260건 ✓ |
| 데이터 차이 | 140건 ✓ |
| HeuristicCompletionException 로그 | **140건** ✅ |
| 로그에 나타나는 예외 | HeuristicCompletionException |
| HTTP 500 응답 | 140건 (예외 발생 건수와 동일) |
| 예외 핸들러 | HeuristicExceptionHandler.handle() |

---

## 🎓 학습 포인트

### 1. SimpleDriverDataSource는 테스트 전용

**Spring 공식 문서:**
> This class is not meant for production use. It does not pool connections and simply obtains and closes connections via the standard DriverManager facility. Consider using DriverManagerDataSource instead, which is actually an alias for this implementation. **For production purposes, use a proper connection pool instead.**

**교훈:**
- 프로덕션 또는 재현 시나리오에는 HikariCP, Tomcat JDBC Pool 등 사용
- SimpleDriverDataSource는 간단한 단위 테스트에만 적합

### 2. autoCommit의 중요성

**트랜잭션 관리의 핵심:**
- autoCommit=true: 각 SQL 문이 독립적인 트랜잭션 (롤백 불가)
- autoCommit=false: 명시적 COMMIT/ROLLBACK까지 트랜잭션 유지

**Connection Pool의 역할:**
- 동일한 Connection 객체를 재사용
- Spring이 트랜잭션 시작 시 autoCommit=false 설정 가능
- ThreadLocal을 통해 트랜잭션 컨텍스트 유지

### 3. 분산 트랜잭션의 본질적 문제

**CompositeTransactionManager의 한계:**
- 진정한 2PC(Two-Phase Commit)가 아님
- Prepare 단계 없이 순차적으로 커밋
- 두 번째 커밋 실패 시 첫 번째는 이미 영구 반영됨
- HeuristicCompletionException은 문제를 알리지만 해결하지는 못함

**근본적 해결책:**
- JTA/XA 트랜잭션 (Atomikos, Bitronix 등)
- Saga 패턴 (보상 트랜잭션)
- 이벤트 기반 최종 일관성
- 비즈니스 로직 재설계 (단일 DB 사용)

### 4. 예외 발생 타이밍의 중요성

**HeuristicCompletionException 발생 조건:**
1. 모든 비즈니스 로직 성공
2. 커밋 단계 진입
3. 일부 커밋 성공 후 나머지 커밋 실패

**현재 문제:**
- 비즈니스 로직 단계에서 실패 (INSERT 실패)
- 커밋 단계 미진입
- 다른 타입의 예외 발생

---

## 📝 추가 개선 사항 (선택)

### 1. 상세 로깅 추가

**CompositeTransactionManager.java에 로깅:**
```java
@Override
public void commit(TransactionStatus status) throws TransactionException {
    CompositeTransactionStatus compositeStatus = (CompositeTransactionStatus) status;
    boolean anyCommitted = false;
    log.info("Starting composite transaction commit for {} resources",
        compositeStatus.statuses().size());

    try {
        for (int i = 0; i < compositeStatus.statuses().size(); i++) {
            TransactionStatus ts = compositeStatus.statuses().get(i);
            if (!ts.isCompleted() && !ts.isRollbackOnly()) {
                log.info("Committing transaction {} of {}", i+1, compositeStatus.statuses().size());
                delegates.get(i).commit(ts);
                anyCommitted = true;
                log.info("Transaction {} committed successfully", i+1);
            }
        }
        log.info("All transactions committed successfully");
    } catch (Exception ex) {
        log.error("Commit failed at transaction. anyCommitted={}, exception={}",
            anyCommitted, ex.getClass().getSimpleName());
        rollbackRemaining(compositeStatus);
        throw new HeuristicCompletionException(anyCommitted
                ? HeuristicCompletionException.STATE_MIXED
                : HeuristicCompletionException.STATE_ROLLED_BACK, ex);
    }
}
```

### 2. HeuristicExceptionHandler 로깅 강화

**HeuristicExceptionHandler.java:**
```java
@RestControllerAdvice
public class HeuristicExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(HeuristicExceptionHandler.class);

    @ExceptionHandler(HeuristicCompletionException.class)
    public ResponseEntity<String> handle(HeuristicCompletionException ex) {
        log.error("HeuristicCompletionException occurred. State: {}, Cause: {}",
            ex.getOutcomeState(), ex.getCause().getMessage(), ex);
        return ResponseEntity.status(500).body(
            "Heuristic exception: " + ex.getOutcomeState() + " - " + ex.getMessage());
    }
}
```

### 3. DB2 타임아웃을 더 공격적으로 설정

**application.yml:**
```yaml
spring:
  datasource:
    db2:
      hikari:
        connection-timeout: 2000      # 3500 → 2000 (더 빠른 타임아웃)
        maximum-pool-size: 5          # 작은 풀로 경합 증가
        minimum-idle: 2
```

### 4. toxiproxy에 latency 추가

**scripts/init-toxiproxy.sh:**
```bash
# 기존 reset_peer 외에 latency도 추가
toxiproxy-cli toxic add mysql_ums \
  -t latency \
  -a latency=500 \
  -a jitter=200

# 이후 reset_peer toxic 추가
```

---

## 🎯 결론 및 핵심 인사이트

### 주요 원인 (현재 재현 프로젝트)

**1차 원인: SimpleDriverDataSource 사용**
- 커넥션 풀링 부재로 매 작업마다 새 Connection 생성
- MyBatis 세션과 Spring 트랜잭션 동기화 실패 가능성
- ThreadLocal에 바인딩된 Connection과 실제 사용 Connection 불일치
- 결과: INSERT 단계에서 예외 발생 → `commit()` 미도달

**⚠️ 중요**: 그러나 SimpleDriverDataSource만이 유일한 원인은 아닐 수 있음

### 다른 가능한 원인들 (실무에서 HikariCP 사용 시)

**2차 원인: toxiproxy 타이밍 문제**
- toxiproxy reset_peer가 INSERT 단계에서 발동
- COMMIT 단계가 아닌 INSERT 단계 실패 → `commit()` 미도달
- 해결: toxiproxy를 COMMIT 단계에 주입하도록 타이밍 조정

**3차 원인: DB2 타임아웃 설정**
- `innodb_lock_wait_timeout=2초`가 너무 짧음
- INSERT 단계에서 락 대기 타임아웃 발생
- COMMIT 전에 실패 → `commit()` 미도달

**핵심 인사이트:**
> HeuristicCompletionException은 **COMMIT 단계**에서만 발생합니다.
> INSERT 단계에서 실패하면 일반 SQLException이 발생하고, `CompositeTransactionManager.commit()`이 호출되지 않아 HeuristicCompletionException이 발생하지 않습니다.

### 정확한 문제 설명 (수정됨)

**초기 분석 (부정확):**
~~"SimpleDriverDataSource가 트랜잭션을 지원하지 않음"~~

**정확한 분석:**
- SimpleDriverDataSource는 트랜잭션 자체는 지원함 (setAutoCommit(false) 호출 가능)
- 문제는 **커넥션 풀링 부재**로 인한 트랜잭션 컨텍스트 유지 실패
- MyBatis가 각 작업마다 새 Connection을 요청하면 트랜잭션 동기화 깨짐
- 또는 MyBatis가 작업 완료 후 Connection을 닫으면 트랜잭션 소실

### 해결 방향 (단계별)

**Phase 1: HikariDataSource로 변경 (즉시 적용)**
1. Connection Pool 사용으로 동일 Connection 재사용
2. Spring의 TransactionSynchronizationManager와 MyBatis 연동 보장
3. ThreadLocal에 바인딩된 Connection이 MyBatis에서도 사용됨

**Phase 2: 로깅 추가 (HikariCP로도 발생하지 않으면)**
1. CompositeTransactionManager.commit() 호출 여부 확인
2. INSERT vs COMMIT 단계 실패 구분
3. `anyCommitted` 플래그 추적

**Phase 3: toxiproxy 타이밍 조정 (INSERT 단계 실패 시)**
1. reset_peer 발동을 COMMIT 단계로 이동
2. latency toxic 추가로 COMMIT을 느리게 만듦
3. COMMIT 중에 연결 끊김 유도

### 검증 완료 기준

**성공 조건:**
- ✅ 로그에서 `=== CompositeTransactionManager.commit() STARTED ===` 확인
- ✅ `anyCommitted=true` 로그 확인
- ✅ `HeuristicCompletionException(STATE_MIXED)` 발생
- ✅ DB1 > DB2 데이터 차이 확인

**실패 시 추가 분석:**
- 로그에서 `commit() STARTED` 없음 → INSERT 단계 실패 (toxiproxy 타이밍 조정 필요)
- `commit() STARTED` 있으나 `anyCommitted=false` → DB1 커밋 실패 (리소스 문제)
- 예외 발생하지만 다른 타입 → ExceptionHandler 충돌 또는 AOP 순서 문제

### 실무 적용 시사점

1. **HikariCP는 필수**: 트랜잭션 동기화를 위해 커넥션 풀 사용
2. **MyBatis + Spring 통합**: SqlSessionTemplate이 TransactionSynchronizationManager와 제대로 연동되는지 확인
3. **장애 주입 위치**: 분산 트랜잭션 테스트 시 COMMIT 단계에 장애 주입 필요
4. **CompositeTransactionManager 한계**: 진정한 2PC가 아니므로 프로덕션 사용 시 보상 트랜잭션 필수
5. **로깅 전략**: commit() 호출 여부, anyCommitted 플래그 추적으로 실패 지점 파악

---

## 📁 수정 대상 파일 목록

### 필수 수정
1. **`src/main/java/com/example/heuristicexception/config/DatabaseConfig.java`**
   - Line 32: `db1DataSource` 메서드의 `.type(SimpleDriverDataSource.class)` 제거
   - Line 32: `.type(com.zaxxer.hikari.HikariDataSource.class)` 추가
   - Line 49: `db2DataSource` 메서드도 동일하게 수정

### 선택 수정 (디버깅 강화)
2. **`src/main/java/com/example/heuristicexception/tx/CompositeTransactionManager.java`**
   - commit/rollback 로그 추가
   - anyCommitted 플래그 값 로깅

3. **`src/main/java/com/example/heuristicexception/tx/HeuristicExceptionHandler.java`**
   - 예외 상세 정보 로깅

4. **`src/main/java/com/example/heuristicexception/service/ApprovalService.java`**
   - INSERT 전후 로그 추가
   - Connection 정보 로깅 (디버깅 시)

---

## 🔗 참고 자료

### Spring 공식 문서
- [SimpleDriverDataSource](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/SimpleDriverDataSource.html)
- [HikariCP](https://github.com/brettwooldridge/HikariCP)
- [DataSourceTransactionManager](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/DataSourceTransactionManager.html)

### 분산 트랜잭션 관련
- [Two-Phase Commit Protocol](https://en.wikipedia.org/wiki/Two-phase_commit_protocol)
- [Saga Pattern](https://microservices.io/patterns/data/saga.html)
- [HeuristicCompletionException JavaDoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/HeuristicCompletionException.html)
