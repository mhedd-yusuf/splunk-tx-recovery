package com.example.txrecovery.config;

import com.example.txrecovery.adapter.splunk.SplQueryBuilder;
import com.example.txrecovery.adapter.splunk.SplunkClient;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;

/** Wires the Splunk {@link WebClient} and the {@link SplQueryBuilder}. */
@Configuration
public class SplunkClientConfig {

    @Bean
    public WebClient splunkWebClient(SplunkProperties props) {
        HttpClient httpClient = HttpClient.create();
        if (props.trustSelfSigned()) {
            // TODO (dev-only): the local Splunk container uses a self-signed certificate.
            // In any non-local environment, this MUST be false and the cert chain
            // must be trusted via the JVM truststore.
            SslContext insecureCtx;
            try {
                insecureCtx = SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .build();
            } catch (SSLException e) {
                throw new IllegalStateException("Failed to build insecure SslContext", e);
            }
            httpClient = httpClient.secure(spec -> spec.sslContext(insecureCtx));
        }
        return WebClient.builder()
                .baseUrl(props.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    public SplunkClient splunkClient(WebClient splunkWebClient, SplunkProperties props) {
        return new SplunkClient(splunkWebClient, props);
    }

    @Bean
    public SplQueryBuilder splQueryBuilder(SplunkProperties props) {
        return new SplQueryBuilder(props.splQuery());
    }
}
