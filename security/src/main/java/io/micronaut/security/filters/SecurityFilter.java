/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.security.filters;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.order.OrderUtil;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.OncePerRequestHttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import io.micronaut.management.endpoint.EndpointsFilter;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.authentication.AuthorizationException;
import io.micronaut.security.config.SecurityConfiguration;
import io.micronaut.security.rules.ReactiveSecurityRule;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.rules.SecurityRuleResult;
import io.micronaut.web.router.RouteMatch;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Security Filter.
 *
 * @author Sergio del Amo
 * @author Graeme Rocher
 * @since 1.0
 */
@Replaces(EndpointsFilter.class)
@Filter(Filter.MATCH_ALL_PATTERN)
public class SecurityFilter extends OncePerRequestHttpServerFilter {

    /**
     * The attribute used to store the authentication object in the request.
     */
    public static final CharSequence AUTHENTICATION = HttpAttributes.PRINCIPAL.toString();

    /**
     * The attribute used to store if the request was rejected and why.
     */
    public static final CharSequence REJECTION = "micronaut.security.REJECTION";

    /**
     * The attribute used to store a valid token in the request.
     */
    public static final CharSequence TOKEN = "micronaut.TOKEN";

    private static final Logger LOG = LoggerFactory.getLogger(SecurityFilter.class);

    /**
     * The order of the Security Filter.
     */
    private static final Integer ORDER = ServerFilterPhase.SECURITY.order();

    /** Combined and sorted list of SecurityRule and ReactiveSecurityRule objects. */
    protected final List<Ordered> orderedSecurityRules;

    protected final Collection<AuthenticationFetcher> authenticationFetchers;

    protected final SecurityConfiguration securityConfiguration;

    /**
     * @param securityRules          The list of security rules that will allow or reject the request
     * @param authenticationFetchers List of {@link AuthenticationFetcher} beans in the context.
     * @param securityConfiguration  The security configuration
     */
    @Deprecated
    public SecurityFilter(Collection<SecurityRule> securityRules,
                          Collection<AuthenticationFetcher> authenticationFetchers,
                          SecurityConfiguration securityConfiguration) {

        this.authenticationFetchers = authenticationFetchers;
        this.securityConfiguration = securityConfiguration;
        this.orderedSecurityRules = new ArrayList<>(securityRules);
        OrderUtil.sort(this.orderedSecurityRules);
    }

    /**
     * @param securityRules The list of security rules that will allow or reject the request
     * @param reactiveSecurityRules The list of reactive security rules that will allow or reject the request
     * @param authenticationFetchers List of {@link AuthenticationFetcher} beans in the context.
     * @param securityConfiguration The security configuration
     */
    @Inject
    public SecurityFilter(Collection<SecurityRule> securityRules,
                          Collection<ReactiveSecurityRule> reactiveSecurityRules,
                          Collection<AuthenticationFetcher> authenticationFetchers,
                          SecurityConfiguration securityConfiguration) {

        this.authenticationFetchers = authenticationFetchers;
        this.securityConfiguration = securityConfiguration;
        this.orderedSecurityRules =
                new ArrayList<>(reactiveSecurityRules.size() + securityRules.size());

        this.orderedSecurityRules.addAll(reactiveSecurityRules);
        this.orderedSecurityRules.addAll(securityRules);
        OrderUtil.sort(this.orderedSecurityRules);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected Publisher<MutableHttpResponse<?>> doFilterOnce(HttpRequest<?> request, ServerFilterChain chain) {
        String method = request.getMethod().toString();
        String path = request.getPath();
        RouteMatch<?> routeMatch = request.getAttribute(HttpAttributes.ROUTE_MATCH, RouteMatch.class).orElse(null);

        return Flowable.fromIterable(authenticationFetchers)
            .flatMap(authenticationFetcher -> authenticationFetcher.fetchAuthentication(request))
            .firstElement()
            .doOnEvent((authentication, throwable) -> {
                if (authentication != null) {
                    request.setAttribute(AUTHENTICATION, authentication);
                    Map<String, Object> attributes = authentication.getAttributes();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Attributes: {}", attributes
                                .entrySet()
                                .stream()
                                .map((entry) -> entry.getKey() + "=>" + entry.getValue().toString())
                                .collect(Collectors.joining(", ")));
                    }
                } else {
                    request.setAttribute(AUTHENTICATION, null);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("No Authentication fetched for request. {} {}.", method, path);
                    }
                }
            })
            .toFlowable()
            .flatMap(authentication -> checkRules(request, chain, routeMatch, authentication))
            .switchIfEmpty(Flowable.defer(() -> checkRules(request, chain, routeMatch, null)));
    }

    /**
     * Check the security rules against the provided arguments.
     *
     * @param request The request
     * @param chain The server chain
     * @param routeMatch The route match
     * @param authentication The authentication
     * @return A response publisher
     */
    protected Publisher<MutableHttpResponse<?>> checkRules(HttpRequest<?> request,
                                                           ServerFilterChain chain,
                                                           @Nullable RouteMatch<?> routeMatch,
                                                           @Nullable Authentication authentication) {
        boolean forbidden = authentication != null;
        String method = request.getMethod().toString();
        String path = request.getPath();
        Map<String, Object> attributes = authentication != null ? authentication.getAttributes() : null;

        return Flowable.fromIterable(orderedSecurityRules)
                .concatMap(
                        ordered -> {
                            if (ordered instanceof SecurityRule) {
                                SecurityRuleResult result =
                                        ((SecurityRule) ordered).check(request, routeMatch, attributes);
                                if (result == SecurityRuleResult.REJECTED || result == SecurityRuleResult.ALLOWED) {
                                    logResult(result, method, path, ordered);
                                    return Publishers.just(result);
                                }
                                return Publishers.empty();
                            } else if (ordered instanceof ReactiveSecurityRule) {
                                return Flowable.fromPublisher(
                                        ((ReactiveSecurityRule) ordered).check(request, routeMatch, attributes))
                                        .firstElement()
                                        // Ideally should return just empty but filter the unknowns
                                        .filter((result) -> result != SecurityRuleResult.UNKNOWN)
                                        .doAfterSuccess((result) -> logResult(result, method, path, ordered))
                                        .toFlowable();
                            } else {
                                // Not sure how we got this object in here so log and return an empty
                                LOG.warn("Unsupported type {}", ordered.getClass());
                                return Publishers.empty();
                            }
                        })
                .firstElement()
                .flatMapPublisher(
                        result -> {
                            if (result == SecurityRuleResult.REJECTED) {
                                request.setAttribute(
                                        REJECTION, forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED);
                                return Publishers.just(new AuthorizationException(authentication));
                            } else if (result == SecurityRuleResult.ALLOWED) {
                                return chain.proceed(request);
                            } else {
                                return Publishers.empty();
                            }
                        })
                .switchIfEmpty(
                        Flowable.defer(
                                () -> {
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug(
                                                "Authorized request {} {}. No rule provider authorized or rejected the request.",
                                                method,
                                                path);
                                    }
                                    // no rule found for the given request
                                    if (routeMatch == null && !securityConfiguration.isRejectNotFound()) {
                                        return chain.proceed(request);
                                    } else {
                                        request.setAttribute(
                                                REJECTION, forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED);
                                        return Publishers.just(new AuthorizationException(authentication));
                                    }
                                }));
    }

    private void logResult(SecurityRuleResult result, String method, String path, Ordered ordered) {
        if (LOG.isDebugEnabled()) {
            if (result == SecurityRuleResult.REJECTED) {
                LOG.debug(
                        "Unauthorized request {} {}. The rule provider {} rejected the request.",
                        method,
                        path,
                        ordered.getClass().getName());
            } else if (result == SecurityRuleResult.ALLOWED) {
                LOG.debug(
                        "Authorized request {} {}. The rule provider {} authorized the request.",
                        method,
                        path,
                        ordered.getClass().getName());
            }
        }
    }
}
