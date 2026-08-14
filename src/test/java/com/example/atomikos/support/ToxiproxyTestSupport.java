package com.example.atomikos.support;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.testcontainers.containers.ToxiproxyContainer;

import java.io.IOException;

/**
 * Helper that owns the two Toxiproxy proxies used in tests:
 * <ul>
 *   <li>Oracle application path</li>
 *   <li>IBM MQ application path</li>
 * </ul>
 *
 * Application Spring Boot connections go through these proxies.
 * Test/admin connections bypass Toxiproxy and connect directly to the containers.
 *
 * Network topology:
 * <pre>
 *   Spring Boot/Atomikos  -->  Toxiproxy (Oracle)  -->  Oracle container
 *   Spring Boot/Atomikos  -->  Toxiproxy (MQ)      -->  IBM MQ container
 *
 *   JUnit verifier  ----------direct---------->  Oracle container
 *   JUnit verifier  ----------direct---------->  IBM MQ container
 * </pre>
 */
public class ToxiproxyTestSupport {

    private final ToxiproxyContainer toxiproxyContainer;
    private Proxy oracleProxy;
    private Proxy mqProxy;

    private int oracleProxyPort;
    private int mqProxyPort;

    public ToxiproxyTestSupport(ToxiproxyContainer toxiproxyContainer) {
        this.toxiproxyContainer = toxiproxyContainer;
    }

    /**
     * Creates both proxies.  Call once after the containers are started.
     *
     * @param oracleHost Oracle container internal network host (within shared Docker network)
     * @param oraclePort Oracle container internal port
     * @param mqHost     IBM MQ container internal network host
     * @param mqPort     IBM MQ container internal port
     */
    public void init(String oracleHost, int oraclePort,
                     String mqHost, int mqPort) throws IOException {

        ToxiproxyClient client = new ToxiproxyClient(
                toxiproxyContainer.getHost(),
                toxiproxyContainer.getControlPort());

        oracleProxy = client.createProxy(
                "oracle",
                "0.0.0.0:8666",
                oracleHost + ":" + oraclePort);

        mqProxy = client.createProxy(
                "ibm-mq",
                "0.0.0.0:8667",
                mqHost + ":" + mqPort);

        oracleProxyPort = toxiproxyContainer.getMappedPort(8666);
        mqProxyPort = toxiproxyContainer.getMappedPort(8667);
    }

    // ------------------------------------------------------------------
    // Mapped host ports (what Spring Boot uses to reach Toxiproxy)
    // ------------------------------------------------------------------

    public String getToxiproxyHost() {
        return toxiproxyContainer.getHost();
    }

    public int getOracleProxyPort() {
        return oracleProxyPort;
    }

    public int getMqProxyPort() {
        return mqProxyPort;
    }

    // ------------------------------------------------------------------
    // Oracle proxy controls
    // ------------------------------------------------------------------

    /** Enable Oracle proxy (normal operation). */
    public void enableOracleProxy() throws IOException {
        oracleProxy.enable();
    }

    /** Disable Oracle proxy – all new TCP connections to the proxy are refused. */
    public void disableOracleProxy() throws IOException {
        oracleProxy.disable();
    }

    /** Remove all toxics from the Oracle proxy, restoring normal operation. */
    public void resetOracleProxy() throws IOException {
        for (eu.rekawek.toxiproxy.model.Toxic toxic : oracleProxy.toxics().getAll()) {
            toxic.remove();
        }
        enableOracleProxy();
    }

    // ------------------------------------------------------------------
    // IBM MQ proxy controls
    // ------------------------------------------------------------------

    /** Enable IBM MQ proxy (normal operation). */
    public void enableMqProxy() throws IOException {
        mqProxy.enable();
    }

    /** Disable IBM MQ proxy. */
    public void disableMqProxy() throws IOException {
        mqProxy.disable();
    }

    /** Remove all toxics from the IBM MQ proxy. */
    public void resetMqProxy() throws IOException {
        for (eu.rekawek.toxiproxy.model.Toxic toxic : mqProxy.toxics().getAll()) {
            toxic.remove();
        }
        enableMqProxy();
    }

    // ------------------------------------------------------------------
    // Convenience: reset everything
    // ------------------------------------------------------------------

    /** Reset both proxies to healthy state. Call in @AfterEach. */
    public void resetAll() throws IOException {
        resetOracleProxy();
        resetMqProxy();
    }
}
