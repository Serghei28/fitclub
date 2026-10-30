import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Генератор локальных секретов FitClub.
 *
 * Запуск (из каталога lab2, нужен только JDK 21, без Maven и зависимостей):
 *   java infrastructure/secrets/GenerateSecrets.java secrets
 *
 * Правила:
 *  - существующие файлы НЕ перезаписываются, поэтому обычный повторный запуск безопасен;
 *  - значения секретов никогда не печатаются, в stdout попадают только имена файлов;
 *  - каталог secrets/ исключён из Git (.gitignore).
 */
public class GenerateSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Владельцы отдельных логических БД. */
    private static final List<String> DB_SERVICES =
            List.of("auth", "schedule", "membership", "booking", "payment", "notification");

    /** Сервисы, которые работают с RabbitMQ (auth-service событий не публикует и не получает). */
    private static final List<String> BROKER_SERVICES =
            List.of("schedule", "membership", "booking", "payment", "notification");

    /** Разрешённые направления внутренних HTTP-вызовов: у каждого направления свой токен. */
    private static final List<String[]> SERVICE_CALLS = List.of(
            new String[] {"schedule", "auth"},
            new String[] {"payment", "membership"},
            new String[] {"booking", "membership"});

    private static final String VHOST = "fitclub";
    private static final String EVENTS_EXCHANGE = "^fitclub\\\\.events$";

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "secrets").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        System.out.println("Каталог секретов: " + dir);

        writeIfAbsent(dir.resolve("postgres_admin_password"), randomToken(24));
        for (String service : DB_SERVICES) {
            writeIfAbsent(dir.resolve("db_" + service + "_password"), randomToken(24));
        }

        writeIfAbsent(dir.resolve("rabbitmq_admin_password"), randomToken(24));
        for (String service : BROKER_SERVICES) {
            writeIfAbsent(dir.resolve("rabbitmq_" + service + "_password"), randomToken(24));
        }

        for (String[] call : SERVICE_CALLS) {
            writeIfAbsent(dir.resolve("service_token_" + call[0] + "_to_" + call[1]), randomToken(32));
        }

        // Пароль первого администратора (auth-service создаёт его при запуске, если ADMIN ещё нет)
        writeIfAbsent(dir.resolve("auth_initial_admin_password"), randomToken(12));

        generateJwtKeys(dir);
        writeRabbitUsers(dir);

        System.out.println("Готово. Значения секретов не выводятся.");
    }

    private static void generateJwtKeys(Path dir) throws IOException, NoSuchAlgorithmException {
        Path privateKey = dir.resolve("jwt_private_key.pem");
        Path publicKey = dir.resolve("jwt_public_key.pem");
        boolean hasPrivate = Files.exists(privateKey);
        boolean hasPublic = Files.exists(publicKey);
        if (hasPrivate && hasPublic) {
            System.out.println("  пропущен (уже существует): jwt_private_key.pem, jwt_public_key.pem");
            return;
        }
        if (hasPrivate || hasPublic) {
            throw new IllegalStateException(
                    "Найден только один из JWT-ключей. Удалите оставшийся файл вручную и перезапустите генератор.");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, RANDOM);
        KeyPair pair = generator.generateKeyPair();
        // PKCS#8 и X.509 SubjectPublicKeyInfo читаются RsaKeyConverters из Spring Security
        writeIfAbsent(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        writeIfAbsent(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    }

    /**
     * Пользователи RabbitMQ импортируются брокером при старте вместе с топологией.
     * В файл попадают только солёные SHA-256 хеши паролей (формат rabbit_password_hashing_sha256).
     * Права минимальны: приложения не объявляют exchange/queue (configure = "^$"),
     * издатели пишут только в fitclub.events, потребители читают только свою очередь.
     */
    private static void writeRabbitUsers(Path dir) throws IOException, NoSuchAlgorithmException {
        Path target = dir.resolve("rabbitmq_users.json");
        if (Files.exists(target)) {
            System.out.println("  пропущен (уже существует): " + target.getFileName());
            return;
        }
        List<String> users = new ArrayList<>();
        List<String> permissions = new ArrayList<>();

        users.add(rabbitUser("fitclub_admin", read(dir.resolve("rabbitmq_admin_password")), "administrator"));
        permissions.add(permission("fitclub_admin", ".*", ".*", ".*"));

        for (String service : BROKER_SERVICES) {
            String user = service + "_service";
            users.add(rabbitUser(user, read(dir.resolve("rabbitmq_" + service + "_password")), null));
            String write = service.equals("notification") ? "^$" : EVENTS_EXCHANGE;
            String read = switch (service) {
                case "membership", "booking", "notification" -> "^" + service + "-service\\\\.events$";
                default -> "^$";
            };
            permissions.add(permission(user, "^$", write, read));
        }

        String json = "{\n"
                + "  \"vhosts\": [{\"name\": \"" + VHOST + "\"}],\n"
                + "  \"users\": [\n    " + String.join(",\n    ", users) + "\n  ],\n"
                + "  \"permissions\": [\n    " + String.join(",\n    ", permissions) + "\n  ]\n"
                + "}\n";
        writeIfAbsent(target, json);
    }

    private static String rabbitUser(String name, String password, String tag) throws NoSuchAlgorithmException {
        byte[] salt = new byte[4];
        RANDOM.nextBytes(salt);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        sha256.update(salt);
        byte[] digest = sha256.digest(password.getBytes(StandardCharsets.UTF_8));
        byte[] saltedHash = new byte[salt.length + digest.length];
        System.arraycopy(salt, 0, saltedHash, 0, salt.length);
        System.arraycopy(digest, 0, saltedHash, salt.length, digest.length);
        String tags = tag == null ? "[]" : "[\"" + tag + "\"]";
        return "{\"name\": \"" + name + "\", \"password_hash\": \"" + Base64.getEncoder().encodeToString(saltedHash)
                + "\", \"hashing_algorithm\": \"rabbit_password_hashing_sha256\", \"tags\": " + tags + "}";
    }

    private static String permission(String user, String configure, String write, String read) {
        return "{\"user\": \"" + user + "\", \"vhost\": \"" + VHOST + "\", \"configure\": \"" + configure
                + "\", \"write\": \"" + write + "\", \"read\": \"" + read + "\"}";
    }

    /** URL-safe случайная строка: подходит для JDBC URL, HTTP-заголовков и SQL-литералов без экранирования. */
    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).strip();
    }

    private static void writeIfAbsent(Path file, String content) throws IOException {
        if (Files.exists(file)) {
            System.out.println("  пропущен (уже существует): " + file.getFileName());
            return;
        }
        // CREATE_NEW: атомарный отказ, если файл появился параллельно
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("  создан: " + file.getFileName());
    }
}
