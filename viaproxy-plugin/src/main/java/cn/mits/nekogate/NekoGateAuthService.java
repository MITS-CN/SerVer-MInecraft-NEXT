package cn.mits.nekogate;

import cn.mits.nekogate.api.NekoGateAPI;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Independent NekoGate account store; it never reads or changes backend login state. */
final class NekoGateAuthService implements NekoGateAPI.AuthenticationService, AutoCloseable {
    private static final int SALT_BYTES = 16, HASH_BYTES = 32;
    private static final int ARGON_MEMORY_KIB = 64 * 1024, ARGON_ITERATIONS = 3, ARGON_PARALLELISM = 1;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY_HASH = createDummyHash();
    private final Object databaseLock = new Object();
    private final Connection database;
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionPlayers = new ConcurrentHashMap<>();
    private final Map<String, String> playerSessions = new ConcurrentHashMap<>();
    private final Set<String> activeConnections = ConcurrentHashMap.newKeySet();
    private final Map<String, String> accountSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAttempts = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Properties config = new Properties();

    NekoGateAuthService(Path databasePath, Path configPath) {
        try {
            Files.createDirectories(databasePath.toAbsolutePath().getParent());
            loadConfig(configPath);
            this.database = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
            try (Statement statement = this.database.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        username TEXT PRIMARY KEY COLLATE NOCASE,
                        password_hash TEXT NOT NULL,
                        failed_attempts INTEGER NOT NULL DEFAULT 0,
                        locked_until INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not initialize NekoGate authentication database", error);
        }
        this.workers = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), runnable -> {
                    Thread thread = new Thread(runnable, "nekogate-auth-worker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    private void loadConfig(Path path) throws java.io.IOException {
        Properties defaults = new Properties();
        defaults.setProperty("password.minLength", "10");
        defaults.setProperty("password.maxLength", "128");
        defaults.setProperty("login.maxFailures", "5");
        defaults.setProperty("login.baseLockoutSeconds", "60");
        defaults.setProperty("login.maxLockoutSeconds", "900");
        defaults.setProperty("login.attemptIntervalMillis", "1000");
        if (Files.exists(path)) try (var input = Files.newInputStream(path)) { this.config.load(input); }
        boolean changed = false;
        for (String key : defaults.stringPropertyNames()) {
            if (!this.config.containsKey(key)) {
                this.config.setProperty(key, defaults.getProperty(key));
                changed = true;
            }
        }
        if (changed || !Files.exists(path)) {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (var output = Files.newOutputStream(path)) {
                this.config.store(output, "NekoGate independent authentication settings");
            }
        }
        for (String key : defaults.stringPropertyNames()) {
            int value;
            try { value = Integer.parseInt(this.config.getProperty(key)); }
            catch (NumberFormatException error) { throw new java.io.IOException("Invalid auth setting: " + key, error); }
            if (value < 1) throw new java.io.IOException("Auth setting must be positive: " + key);
        }
        if (integer("password.minLength", 10) > integer("password.maxLength", 128)
                || integer("login.baseLockoutSeconds", 60) > integer("login.maxLockoutSeconds", 900)) {
            throw new java.io.IOException("Invalid NekoGate authentication limits");
        }
    }

    boolean handleCommand(NekoGateAPI.Player player, String input, String sessionId, Consumer<String> reply) {
        if (player == null || player.name() == null || input == null || sessionId == null) return false;
        if (!this.activeConnections.contains(sessionId)) return false;
        List<String> tokens = NekoGateCommandManager.tokenize(input);
        if (tokens.isEmpty()) return false;
        String root = tokens.get(0).toLowerCase(Locale.ROOT);
        int namespace = root.lastIndexOf(':');
        if (namespace >= 0) root = root.substring(namespace + 1);
        if (!root.equals("ng")) return false;
        Consumer<String> send = reply == null ? ignored -> { } : reply;
        if (!allowAttempt(sessionId)) {
            send.accept("§cPlease wait before using another NekoGate authentication command.");
            return true;
        }
        String sub = tokens.size() > 1 ? tokens.get(1).toLowerCase(Locale.ROOT) : "help";
        List<String> args = tokens.size() > 2 ? tokens.subList(2, tokens.size()) : List.of();
        switch (sub) {
            case "register" -> {
                if (args.size() != 3) send.accept("§eUsage: /ng register <account> <password> <confirm-password>");
                else submit(send, () -> register(args.get(0), args.get(1), args.get(2), player.name(), sessionId));
            }
            case "login" -> {
                if (args.size() != 2) send.accept("§eUsage: /ng login <account> <password>");
                else submit(send, () -> login(args.get(0), args.get(1), player.name(), sessionId));
            }
            case "logout" -> send.accept(logout(sessionId)
                    ? "§aYou have logged out of NekoGate." : "§eYou are not logged in to NekoGate.");
            case "changepassword" -> {
                if (args.size() != 2) send.accept("§eUsage: /ng changepassword <old-password> <new-password>");
                else submit(send, () -> changePassword(args.get(0), args.get(1), sessionId));
            }
            case "status" -> send.accept(isAuthenticated(sessionId)
                    ? "§aNekoGate account: authenticated" : "§eNekoGate account: not authenticated");
            case "help" -> send.accept("§eNekoGate: /ng register <account> <password> <confirm>, /ng login <account> <password>, /ng logout, /ng changepassword <old> <new>, /ng status");
            default -> send.accept("§eUnknown NekoGate subcommand. Use /ng for help.");
        }
        return true;
    }

    private boolean allowAttempt(String sessionId) {
        long now = System.nanoTime();
        long interval = TimeUnit.MILLISECONDS.toNanos(integer("login.attemptIntervalMillis", 1000));
        Long previous = this.lastAttempts.put(sessionId, now);
        return previous == null || now - previous >= interval;
    }

    private void submit(Consumer<String> reply, Callable<String> operation) {
        if (this.closed.get()) { reply.accept("§cNekoGate authentication is shutting down."); return; }
        try {
            this.workers.execute(() -> {
                try { reply.accept(operation.call()); }
                catch (IllegalArgumentException invalid) {
                    reply.accept("§c" + (invalid.getMessage() == null ? "Invalid authentication input." : invalid.getMessage()));
                }
                catch (Exception error) {
                    System.err.println("[NekoGate][AUTH] Operation failed: " + error.getClass().getSimpleName());
                    reply.accept("§cNekoGate authentication failed due to an internal error.");
                }
            });
        } catch (RejectedExecutionException busy) {
            reply.accept("§cNekoGate authentication is busy. Please try again shortly.");
        }
    }

    private String register(String accountName, String password, String confirmation, String playerName, String sessionId) throws Exception {
        if (isAuthenticated(sessionId)) return "§eLog out of the current NekoGate account first.";
        String username = normalizeUsername(accountName);
        validatePassword(password);
        if (!password.equals(confirmation)) return "§cPasswords do not match.";
        String encoded = hashPassword(password.toCharArray());
        synchronized (this.databaseLock) {
            try (PreparedStatement statement = this.database.prepareStatement(
                    "INSERT INTO accounts(username,password_hash,created_at,updated_at) VALUES(?,?,?,?)")) {
                String now = Instant.now().toString();
                statement.setString(1, username); statement.setString(2, encoded);
                statement.setString(3, now); statement.setString(4, now);
                statement.executeUpdate();
            } catch (SQLException duplicate) {
                if (isConstraintViolation(duplicate)) return "§cA NekoGate account with this name already exists.";
                throw duplicate;
            }
        }
        if (!createSession(username, playerName, sessionId)) return "§cAccount registered, but this account or player session is already active.";
        return "§aNekoGate account registered and logged in. This password is separate from your server password.";
    }

    private String login(String accountName, String password, String playerName, String sessionId) throws Exception {
        String username = normalizeUsername(accountName);
        if (isAuthenticated(sessionId)) return "§eYou are already logged in to NekoGate.";
        Account account;
        synchronized (this.databaseLock) {
            try (PreparedStatement statement = this.database.prepareStatement(
                    "SELECT password_hash,failed_attempts,locked_until FROM accounts WHERE username=?")) {
                statement.setString(1, username);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        account = new Account(DUMMY_HASH, 0, 0, false);
                    } else {
                        account = new Account(result.getString(1), result.getInt(2), result.getLong(3), true);
                    }
                }
            }
        }
        if (!passwordWithinMaximum(password)) return "§cInvalid NekoGate account or password.";
        if (account.lockedUntil() > Instant.now().getEpochSecond()) {
            verifyPassword(password.toCharArray(), DUMMY_HASH);
            return "§cInvalid NekoGate account or password.";
        }
        boolean passwordMatches = verifyPassword(password.toCharArray(), account.passwordHash());
        if (!passwordMatches) {
            if (account.exists()) recordFailure(username, account.failedAttempts());
            return "§cInvalid NekoGate account or password.";
        }
        if (!account.exists()) return "§cInvalid NekoGate account or password.";
        synchronized (this.databaseLock) {
            try (PreparedStatement statement = this.database.prepareStatement(
                    "UPDATE accounts SET failed_attempts=0,locked_until=0,updated_at=? WHERE username=?")) {
                statement.setString(1, Instant.now().toString()); statement.setString(2, username);
                statement.executeUpdate();
            }
        }
        if (!createSession(username, playerName, sessionId)) return "§cThis account or player session is already active.";
        return "§aNekoGate login successful.";
    }

    private String changePassword(String oldPassword, String newPassword, String sessionId) throws Exception {
        String username = this.sessions.get(sessionId);
        if (username == null) return "§cLog in to NekoGate first.";
        if (!passwordWithinMaximum(oldPassword)) return "§cThe old NekoGate password is incorrect.";
        validatePassword(newPassword);
        String stored;
        synchronized (this.databaseLock) {
            try (PreparedStatement statement = this.database.prepareStatement("SELECT password_hash FROM accounts WHERE username=?")) {
                statement.setString(1, username);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return "§cNekoGate account not found.";
                    stored = result.getString(1);
                }
            }
        }
        if (!verifyPassword(oldPassword.toCharArray(), stored)) return "§cThe old NekoGate password is incorrect.";
        String replacement = hashPassword(newPassword.toCharArray());
        synchronized (this.databaseLock) {
            try (PreparedStatement statement = this.database.prepareStatement(
                    "UPDATE accounts SET password_hash=?,failed_attempts=0,locked_until=0,updated_at=? WHERE username=?")) {
                statement.setString(1, replacement); statement.setString(2, Instant.now().toString());
                statement.setString(3, username); statement.executeUpdate();
            }
        }
        return "§aNekoGate password changed.";
    }

    private void recordFailure(String username, int previousFailures) throws SQLException {
        int failures = previousFailures + 1, threshold = integer("login.maxFailures", 5);
        long lockUntil = 0;
        if (failures >= threshold) {
            int exponent = Math.min(20, failures - threshold);
            long duration = Math.min(integer("login.maxLockoutSeconds", 900),
                    (long) integer("login.baseLockoutSeconds", 60) * (1L << exponent));
            lockUntil = Instant.now().getEpochSecond() + duration;
        }
        synchronized (this.databaseLock) {
            try (PreparedStatement statement = this.database.prepareStatement(
                    "UPDATE accounts SET failed_attempts=?,locked_until=?,updated_at=? WHERE username=?")) {
                statement.setInt(1, failures); statement.setLong(2, lockUntil);
                statement.setString(3, Instant.now().toString()); statement.setString(4, username);
                statement.executeUpdate();
            }
        }
    }

    void openConnection(String sessionId) {
        if (sessionId == null || this.closed.get()) return;
        synchronized (this.accountSessions) { this.activeConnections.add(sessionId); }
    }

    boolean isConnectionActive(String sessionId) {
        return sessionId != null && this.activeConnections.contains(sessionId) && !this.closed.get();
    }

    private boolean createSession(String username, String playerName, String sessionId) {
        if (playerName == null || playerName.isBlank()) return false;
        String playerKey = playerName.toLowerCase(Locale.ROOT);
        synchronized (this.accountSessions) {
            if (!isConnectionActive(sessionId)) return false;
            String existingAccount = this.sessions.get(sessionId);
            if (existingAccount != null && !existingAccount.equals(username)) return false;
            String existingAccountSession = this.accountSessions.get(username);
            if (existingAccountSession != null && !existingAccountSession.equals(sessionId)) return false;
            String existingPlayerSession = this.playerSessions.get(playerKey);
            if (existingPlayerSession != null && !existingPlayerSession.equals(sessionId)) return false;
            this.accountSessions.put(username, sessionId);
            this.sessions.put(sessionId, username);
            this.sessionPlayers.put(sessionId, playerKey);
            this.playerSessions.put(playerKey, sessionId);
            return true;
        }
    }

    private boolean logout(String sessionId) {
        synchronized (this.accountSessions) {
            String username = this.sessions.remove(sessionId);
            String playerKey = this.sessionPlayers.remove(sessionId);
            this.lastAttempts.remove(sessionId);
            if (username == null) return false;
            this.accountSessions.remove(username, sessionId);
            if (playerKey != null) this.playerSessions.remove(playerKey, sessionId);
            return true;
        }
    }

    void disconnect(String sessionId) {
        if (sessionId != null) {
            synchronized (this.accountSessions) {
                this.activeConnections.remove(sessionId);
                logout(sessionId);
            }
        }
    }
    boolean isAuthenticated(String sessionId) { return sessionId != null && this.sessions.containsKey(sessionId); }

    @Override public boolean isAuthenticated(NekoGateAPI.Player player) {
        if (player == null || player.name() == null) return false;
        String sessionId = this.playerSessions.get(player.name().toLowerCase(Locale.ROOT));
        return sessionId != null && this.sessions.containsKey(sessionId);
    }

    private static String normalizeUsername(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{3,32}")) {
            throw new IllegalArgumentException("NekoGate account names must be 3-32 letters, digits, or underscores.");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private boolean passwordWithinMaximum(String password) {
        return password != null && password.codePointCount(0, password.length()) <= integer("password.maxLength", 128);
    }

    private void validatePassword(String password) {
        if (password == null) throw new IllegalArgumentException("Password is required.");
        int length = password.codePointCount(0, password.length());
        if (length < integer("password.minLength", 10) || length > integer("password.maxLength", 128)) {
            throw new IllegalArgumentException("Password length is outside configured limits.");
        }
    }

    private static String createDummyHash() {
        return hashPassword("NekoGate timing equalization secret".toCharArray());
    }

    private static String hashPassword(char[] password) {
        byte[] salt = new byte[SALT_BYTES], hash = new byte[HASH_BYTES];
        RANDOM.nextBytes(salt);
        try {
            Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13).withIterations(ARGON_ITERATIONS)
                    .withMemoryAsKB(ARGON_MEMORY_KIB).withParallelism(ARGON_PARALLELISM).withSalt(salt).build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(parameters); generator.generateBytes(password, hash);
            return "argon2id$v=19$m=65536,t=3,p=1$"
                    + Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
                    + Base64.getEncoder().withoutPadding().encodeToString(hash);
        } finally { Arrays.fill(password, '\0'); Arrays.fill(hash, (byte) 0); }
    }

    private static boolean verifyPassword(char[] password, String encoded) {
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 5 || !parts[0].equals("argon2id") || !parts[1].equals("v=19")
                    || !parts[2].equals("m=65536,t=3,p=1")) return false;
            byte[] salt = Base64.getDecoder().decode(parts[3]), expected = Base64.getDecoder().decode(parts[4]);
            if (salt.length != SALT_BYTES || expected.length != HASH_BYTES) return false;
            byte[] actual = new byte[HASH_BYTES];
            Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13).withIterations(ARGON_ITERATIONS)
                    .withMemoryAsKB(ARGON_MEMORY_KIB).withParallelism(ARGON_PARALLELISM).withSalt(salt).build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(parameters); generator.generateBytes(password, actual);
            boolean matches = MessageDigest.isEqual(expected, actual);
            Arrays.fill(actual, (byte) 0); Arrays.fill(expected, (byte) 0);
            return matches;
        } catch (RuntimeException malformed) { return false; }
        finally { Arrays.fill(password, '\0'); }
    }

    private int integer(String key, int fallback) {
        try { return Integer.parseInt(this.config.getProperty(key, Integer.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static boolean isConstraintViolation(SQLException error) {
        return error.getErrorCode() == 19 || (error.getMessage() != null
                && error.getMessage().toLowerCase(Locale.ROOT).contains("constraint"));
    }
    private record Account(String passwordHash, int failedAttempts, long lockedUntil, boolean exists) { }

    @Override public void close() {
        if (!this.closed.compareAndSet(false, true)) return;
        this.workers.shutdownNow(); this.sessions.clear(); this.sessionPlayers.clear();
        this.playerSessions.clear(); this.accountSessions.clear(); this.activeConnections.clear(); this.lastAttempts.clear();
        synchronized (this.databaseLock) { try { this.database.close(); } catch (SQLException ignored) { } }
    }
}
