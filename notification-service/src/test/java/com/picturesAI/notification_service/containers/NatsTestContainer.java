package com.picturesAI.notification_service.containers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class NatsTestContainer extends GenericContainer<NatsTestContainer> {

    public static final int CLIENT_PORT = 4222;
    public static final String USER = "picturesAI";
    public static final String PASS = "picturesAI";

    public NatsTestContainer() {
        super(DockerImageName.parse("nats:2.10"));
        withExposedPorts(CLIENT_PORT);
        withCommand("-p", String.valueOf(CLIENT_PORT), "-user", USER, "-pass", PASS);
        waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
    }

    public String connectionUrl() {
        return "nats://" + getHost() + ":" + getMappedPort(CLIENT_PORT);
    }
}