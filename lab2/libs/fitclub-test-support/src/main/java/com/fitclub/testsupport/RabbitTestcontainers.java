package com.fitclub.testsupport;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Настоящий RabbitMQ той же версии, что в docker-compose.yml, с топологией из
 * infrastructure/rabbitmq/topology.json (exchange, quorum-очереди, DLX, bindings).
 * Один контейнер на JVM: тесты одного модуля используют его совместно.
 */
public final class RabbitTestcontainers {

    public static final String IMAGE = "rabbitmq:4.1.8-management-alpine";
    public static final String VHOST = "fitclub";

    private static RabbitMQContainer container;

    private RabbitTestcontainers() {
    }

    public static synchronized RabbitMQContainer container() {
        if (container == null) {
            RabbitMQContainer started = new RabbitMQContainer(IMAGE);
            started.start();
            applyTopology(started);
            container = started;
        }
        return container;
    }

    /** Подключение приложения к тестовому брокеру (пользователь guest, vhost fitclub). */
    public static void register(DynamicPropertyRegistry registry) {
        RabbitMQContainer rabbit = container();
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> VHOST);
    }

    /** Остановка/запуск приложения RabbitMQ внутри контейнера: порт сохраняется, клиенты переподключаются. */
    public static void stopBroker() {
        exec("rabbitmqctl", "stop_app");
    }

    public static void startBroker() {
        exec("rabbitmqctl", "start_app");
        exec("rabbitmqctl", "await_startup");
    }

    private static void applyTopology(RabbitMQContainer rabbit) {
        try {
            rabbit.execInContainer("rabbitmqctl", "add_vhost", VHOST);
            rabbit.execInContainer("rabbitmqctl", "set_permissions", "-p", VHOST, rabbit.getAdminUsername(),
                    ".*", ".*", ".*");
            String json = Files.readString(findTopology());
            CachingConnectionFactory factory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
            factory.setUsername(rabbit.getAdminUsername());
            factory.setPassword(rabbit.getAdminPassword());
            factory.setVirtualHost(VHOST);
            RabbitAdmin admin = new RabbitAdmin(factory);
            List<Map<String, Object>> exchanges = JsonPath.read(json, "$.exchanges");
            for (Map<String, Object> e : exchanges) {
                String name = (String) e.get("name");
                admin.declareExchange("topic".equals(e.get("type"))
                        ? new TopicExchange(name, true, false)
                        : new DirectExchange(name, true, false));
            }
            List<Map<String, Object>> queues = JsonPath.read(json, "$.queues");
            for (Map<String, Object> q : queues) {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = new HashMap<>((Map<String, Object>) q.get("arguments"));
                admin.declareQueue(new Queue((String) q.get("name"), true, false, false, args));
            }
            List<Map<String, Object>> bindings = JsonPath.read(json, "$.bindings");
            for (Map<String, Object> b : bindings) {
                admin.declareBinding(new Binding((String) b.get("destination"), Binding.DestinationType.QUEUE,
                        (String) b.get("source"), (String) b.get("routing_key"), null));
            }
            factory.destroy();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot apply RabbitMQ topology", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Ищет lab2/infrastructure/rabbitmq/topology.json вверх от рабочего каталога теста. */
    static Path findTopology() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            Path candidate = dir.toPath().resolve("infrastructure/rabbitmq/topology.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("infrastructure/rabbitmq/topology.json not found");
    }

    private static void exec(String... command) {
        try {
            var result = container().execInContainer(command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Command failed: " + String.join(" ", command) + ": "
                        + result.getStderr());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
