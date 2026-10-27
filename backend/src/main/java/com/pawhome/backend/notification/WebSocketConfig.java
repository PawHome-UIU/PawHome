package com.pawhome.backend.notification;

import com.pawhome.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;

/**
 * The socket the bell listens on.
 *
 * spring-boot-starter-websocket has been in pom.xml since the first commit and
 * nothing used it. This is what it was added for: a notification that appears
 * without a refresh, rather than a page that polls and is wrong in between.
 *
 * THE SIMPLE BROKER, NOT AN EXTERNAL ONE. enableSimpleBroker keeps the
 * subscription registry in this JVM's memory, which is correct for one
 * instance and wrong for two -- a second instance would not know about the
 * first's subscribers, so a user connected to A would never see an event
 * raised on B. Saying so here because the fix is a real broker (RabbitMQ,
 * ActiveMQ) and one line of config, and the day this runs behind a load
 * balancer somebody has to know that.
 *
 * AUTHENTICATION HAPPENS ON THE STOMP CONNECT FRAME, NOT ON THE HTTP
 * HANDSHAKE, and that is forced by the browser rather than chosen: the
 * WebSocket API gives no way to set an Authorization header on the opening
 * request. The alternative is a token in the query string, which lands in
 * every access log and proxy trace along the way. A CONNECT header is
 * application-level, so it stays out of them.
 *
 * The handshake itself is therefore permitted in SecurityConfig and carries no
 * identity. That is safe because it grants nothing: a socket with no CONNECT
 * accepted can neither subscribe nor receive, and convertAndSendToUser only
 * ever addresses the principal established below.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final JwtService jwtService;

    @Value("${app.cors.allowed-origins:http://localhost:5174}")
    private String[] allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // The same origin list the REST API uses. A socket that accepted any
        // origin would be a way around the CORS rules on everything else.
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /queue rather than /topic: every destination here is addressed to
        // one person. A topic is a broadcast, and there is nothing in this
        // application that everybody should receive.
        registry.enableSimpleBroker("/queue");
        registry.setUserDestinationPrefix("/user");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                // Only CONNECT carries credentials. Every later frame on the
                // session inherits the principal set here, which is why this
                // does not have to re-check on SUBSCRIBE.
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String header = accessor.getFirstNativeHeader("Authorization");
                    String token = header != null && header.startsWith("Bearer ")
                            ? header.substring(7) : null;
                    Long userId = token == null ? null : jwtService.userIdFrom(token);

                    if (userId == null) {
                        // Refusing here closes the session. Letting an
                        // unauthenticated CONNECT through would give a socket
                        // that silently receives nothing, which is far harder
                        // to diagnose from the browser than a refusal.
                        log.debug("Refused a STOMP CONNECT with no usable token");
                        throw new org.springframework.messaging.MessagingException(
                                "Sign in before connecting.");
                    }
                    accessor.setUser(new StompPrincipal(String.valueOf(userId)));
                }
                return message;
            }
        });
    }

    /**
     * The name IS the user id, and that is load-bearing.
     *
     * convertAndSendToUser(name, ...) routes by Principal.getName(), so
     * NotificationService passing String.valueOf(userId) and this returning
     * the same string is what makes a frame reach the right browser. Using the
     * email here instead would work until somebody changed theirs.
     */
    private record StompPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
