/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.knox.gateway.service.knoxtoken;

import java.nio.charset.StandardCharsets;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import javax.annotation.PostConstruct;
import javax.inject.Singleton;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.util.ByteUtils;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.authorize.AuthorizationException;
import org.apache.hadoop.security.authorize.ProxyUsers;
import org.apache.knox.gateway.config.GatewayConfig;
import org.apache.knox.gateway.i18n.messages.MessagesFactory;
import org.apache.knox.gateway.security.SubjectUtils;
import org.apache.knox.gateway.services.ServiceType;
import org.apache.knox.gateway.services.GatewayServices;
import org.apache.knox.gateway.services.ServiceLifecycleException;
import org.apache.knox.gateway.services.security.AliasService;
import org.apache.knox.gateway.services.security.AliasServiceException;
import org.apache.knox.gateway.services.security.KeystoreService;
import org.apache.knox.gateway.services.security.KeystoreServiceException;
import org.apache.knox.gateway.services.security.token.JWTokenAttributes;
import org.apache.knox.gateway.services.security.token.JWTokenAttributesBuilder;
import org.apache.knox.gateway.services.security.token.JWTokenAuthority;
import org.apache.knox.gateway.services.security.token.KnoxToken;
import org.apache.knox.gateway.services.security.token.TokenMetadata;
import org.apache.knox.gateway.services.security.token.TokenServiceException;
import org.apache.knox.gateway.services.security.token.TokenStateService;
import org.apache.knox.gateway.services.security.token.TokenUtils;
import org.apache.knox.gateway.services.security.token.UnknownTokenException;
import org.apache.knox.gateway.services.security.token.impl.JWT;
import org.apache.knox.gateway.services.security.token.impl.JWTToken;
import org.apache.knox.gateway.services.security.token.impl.TokenMAC;
import org.apache.knox.gateway.util.AuthFilterUtils;
import org.apache.knox.gateway.util.JsonUtils;
import org.apache.knox.gateway.util.Tokens;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_XML;

@Singleton
@Path(TokenResource.RESOURCE_PATH)
public class TokenResource {
  static final String LIFESPAN = "lifespan";
  static final String COMMENT = "comment";
  private static final String EXPIRES_IN = "expires_in";
  private static final String TOKEN_TYPE = "token_type";
  private static final String ACCESS_TOKEN = "access_token";
  private static final String TOKEN_ID = "token_id";
  private static final String PASSCODE = "passcode";
  private static final String MANAGED_TOKEN = "managed";
  private static final String TARGET_URL = "target_url";
  private static final String ENDPOINT_PUBLIC_CERT = "endpoint_public_cert";
  private static final String BEARER = "Bearer";
  private static final String TOKEN_TTL_PARAM = "knox.token.ttl";
  private static final String TOKEN_TYPE_PARAM = "knox.token.type";
  private static final String TOKEN_AUDIENCES_PARAM = "knox.token.audiences";
  private static final String TOKEN_TARGET_URL = "knox.token.target.url";
  static final String TOKEN_CLIENT_DATA = "knox.token.client.data";
  private static final String TOKEN_CLIENT_CERT_REQUIRED = "knox.token.client.cert.required";
  private static final String TOKEN_ALLOWED_PRINCIPALS = "knox.token.allowed.principals";
  private static final String TOKEN_SIG_ALG = "knox.token.sigalg";
  private static final String TOKEN_EXP_RENEWAL_INTERVAL = "knox.token.exp.renew-interval";
  private static final String TOKEN_EXP_RENEWAL_MAX_LIFETIME = "knox.token.exp.max-lifetime";
  private static final String TOKEN_EXP_TOKENGEN_ALLOWED_TSS_BACKENDS = "knox.token.exp.tokengen.allowed.tss.backends";
  private static final String TOKEN_RENEWER_WHITELIST = "knox.token.renewer.whitelist";
  private static final String TSS_STATUS_IS_MANAGEMENT_ENABLED = "tokenManagementEnabled";
  private static final String TSS_STATUS_CONFIFURED_BACKEND = "configuredTssBackend";
  private static final String TSS_STATUS_ACTUAL_BACKEND = "actualTssBackend";
  private static final String TSS_ALLOWED_BACKEND_FOR_TOKENGEN = "allowedTssForTokengen";
  private static final String TSS_MAXIMUM_LIFETIME_SECONDS = "maximumLifetimeSeconds";
  private static final String TSS_MAXIMUM_LIFETIME_TEXT = "maximumLifetimeText";
  private static final String LIFESPAN_INPUT_ENABLED_PARAM = "knox.token.lifespan.input.enabled";
  private static final String LIFESPAN_INPUT_ENABLED_TEXT = "lifespanInputEnabled";
  static final String KNOX_TOKEN_USER_LIMIT_EXCEEDED_ACTION = "knox.token.user.limit.exceeded.action";
  private static final String METADATA_QUERY_PARAM_PREFIX = "md_";
  private static final long TOKEN_TTL_DEFAULT = 30000L;
  static final String TOKEN_API_PATH = "knoxtoken/api/v1";
  static final String RESOURCE_PATH = TOKEN_API_PATH + "/token";
  static final String GET_USER_TOKENS = "/getUserTokens";
  static final String GET_TSS_STATUS_PATH = "/getTssStatus";
  static final String RENEW_PATH = "/renew";
  static final String REVOKE_PATH = "/revoke";
  static final String ENABLE_PATH = "/enable";
  static final String DISABLE_PATH = "/disable";
  private static final String TARGET_ENDPOINT_PULIC_CERT_PEM = "knox.token.target.endpoint.cert.pem";
  static final String QUERY_PARAMETER_DOAS = "doAs";
  static final String PROXYUSER_PREFIX = "knox.token.proxyuser";

  private static TokenServiceMessages log = MessagesFactory.get(TokenServiceMessages.class);
  private long tokenTTL = TOKEN_TTL_DEFAULT;
  private String tokenType;
  private String tokenTTLAsText;
  private List<String> targetAudiences = new ArrayList<>();
  private String tokenTargetUrl;
  private Map<String, Object> tokenClientDataMap;
  private List<String> allowedDNs = new ArrayList<>();
  private boolean clientCertRequired;
  private String signatureAlgorithm;
  private String endpointPublicCert;

  // Optional token store service
  private TokenStateService tokenStateService;
  private TokenMAC tokenMAC;
  private final Map<String, String> tokenStateServiceStatusMap = new HashMap<>();

  private Optional<Long> renewInterval = Optional.empty();

  private Optional<Long> maxTokenLifetime = Optional.empty();

  private int tokenLimitPerUser;

  enum UserLimitExceededAction {REMOVE_OLDEST, RETURN_ERROR};
  private UserLimitExceededAction userLimitExceededAction = UserLimitExceededAction.RETURN_ERROR;

  private List<String> allowedRenewers;

  @Context
  HttpServletRequest request;

  @Context
  ServletContext context;

  public enum ErrorCode {
    UNKNOWN(0),
    CONFIGURATION_ERROR(10),
    UNAUTHORIZED(20),
    INTERNAL_ERROR(30),
    INVALID_TOKEN(40),
    UNKNOWN_TOKEN(50),
    ALREADY_DISABLED(60),
    ALREADY_ENABLED(70);

    private final int code;

    ErrorCode(int code) {
      this.code = code;
    }

    public int toInt() {
      return code;
    }
  }

  @PostConstruct
  public void init() throws AliasServiceException, ServiceLifecycleException, KeyLengthException {

    String audiences = context.getInitParameter(TOKEN_AUDIENCES_PARAM);
    if (audiences != null) {
      String[] auds = audiences.split(",");
      for (String aud : auds) {
        targetAudiences.add(aud.trim());
      }
    }

    String clientCert = context.getInitParameter(TOKEN_CLIENT_CERT_REQUIRED);
    clientCertRequired = "true".equals(clientCert);

    String principals = context.getInitParameter(TOKEN_ALLOWED_PRINCIPALS);
    if (principals != null) {
      String[] dns = principals.split(";");
      for (String dn : dns) {
        allowedDNs.add(dn.replaceAll("\\s+", ""));
      }
    }

    String ttl = context.getInitParameter(TOKEN_TTL_PARAM);
    if (ttl != null) {
      try {
        tokenTTL = Long.parseLong(ttl);
        if (tokenTTL < -1 || (tokenTTL + System.currentTimeMillis() < 0)) {
          log.invalidTokenTTLEncountered(ttl);
          tokenTTL = TOKEN_TTL_DEFAULT;
        }
      } catch (NumberFormatException nfe) {
        log.invalidTokenTTLEncountered(ttl);
      }
    }

    this.tokenType = context.getInitParameter(TOKEN_TYPE_PARAM);

    tokenTTLAsText = getTokenTTLAsText();

    tokenTargetUrl = context.getInitParameter(TOKEN_TARGET_URL);

    String clientData = context.getInitParameter(TOKEN_CLIENT_DATA);
    if (clientData != null) {
      tokenClientDataMap = new HashMap<>();
      String[] tokenClientData = clientData.split(",");
      addClientDataToMap(tokenClientData, tokenClientDataMap);
    }

    setSignatureAlogrithm();

    String targetEndpointPublicCert = context.getInitParameter(TARGET_ENDPOINT_PULIC_CERT_PEM);
    if (targetEndpointPublicCert != null) {
      endpointPublicCert = targetEndpointPublicCert;
    }

    // If server-managed token expiration is configured, set the token state service
    if (isServerManagedTokenStateEnabled()) {
      String topologyName = getTopologyName();
      log.serverManagedTokenStateEnabled(topologyName);

      GatewayServices services = (GatewayServices) context.getAttribute(GatewayServices.GATEWAY_SERVICES_ATTRIBUTE);
      tokenStateService = services.getService(ServiceType.TOKEN_STATE_SERVICE);
      final GatewayConfig gatewayConfig = (GatewayConfig) context.getAttribute(GatewayConfig.GATEWAY_CONFIG_ATTRIBUTE);
      final AliasService aliasService = services.getService(ServiceType.ALIAS_SERVICE);
      tokenMAC = new TokenMAC(gatewayConfig.getKnoxTokenHashAlgorithm(), aliasService.getPasswordFromAliasForGateway(TokenMAC.KNOX_TOKEN_HASH_KEY_ALIAS_NAME));

      tokenLimitPerUser = gatewayConfig.getMaximumNumberOfTokensPerUser();
      final String userLimitExceededActionParam = context.getInitParameter(KNOX_TOKEN_USER_LIMIT_EXCEEDED_ACTION);
      if (userLimitExceededActionParam != null) {
        userLimitExceededAction = UserLimitExceededAction.valueOf(userLimitExceededActionParam);
        log.generalInfoMessage("Configured Knox Token user limit exceeded action = " + userLimitExceededAction.name());
      }

      String renewIntervalValue = context.getInitParameter(TOKEN_EXP_RENEWAL_INTERVAL);
      if (renewIntervalValue != null && !renewIntervalValue.isEmpty()) {
        try {
          renewInterval = Optional.of(Long.parseLong(renewIntervalValue));
        } catch (NumberFormatException e) {
          log.invalidConfigValue(topologyName, TOKEN_EXP_RENEWAL_INTERVAL, renewIntervalValue, e);
        }
      }

      String maxLifetimeValue = context.getInitParameter(TOKEN_EXP_RENEWAL_MAX_LIFETIME);
      if (maxLifetimeValue != null && !maxLifetimeValue.isEmpty()) {
        try {
          maxTokenLifetime = Optional.of(Long.parseLong(maxLifetimeValue));
        } catch (NumberFormatException e) {
          log.invalidConfigValue(topologyName, TOKEN_EXP_RENEWAL_MAX_LIFETIME, maxLifetimeValue, e);
        }
      }

      allowedRenewers = new ArrayList<>();
      String renewerList = context.getInitParameter(TOKEN_RENEWER_WHITELIST);
      if (renewerList != null && !renewerList.isEmpty()) {
        for (String renewer : renewerList.split(",")) {
          allowedRenewers.add(renewer.trim());
        }
      } else {
        log.noRenewersConfigured(topologyName);
      }
    }
    setTokenStateServiceStatusMap();

    final Configuration conf = AuthFilterUtils.getProxyUserConfiguration(context, PROXYUSER_PREFIX);
    ProxyUsers.refreshSuperUserGroupsConfiguration(conf, PROXYUSER_PREFIX);
  }

  private String getTokenTTLAsText() {
    if (tokenTTL == -1) {
      return "Unlimited lifetime";
    }

    final Duration tokenTTLDuration = Duration.ofMillis(tokenTTL);
    long daysPart = tokenTTLDuration.toDays();
    long hoursPart = daysPart > 0 ? tokenTTLDuration.minusDays(daysPart).toHours() : tokenTTLDuration.toHours();
    long minutesPart = tokenTTLDuration.toHours() > 0 ? tokenTTLDuration.minusHours(tokenTTLDuration.toHours()).toMinutes() : tokenTTLDuration.toMinutes();
    long secondsPart = tokenTTLDuration.toMinutes() > 0 ? tokenTTLDuration.minusMinutes(tokenTTLDuration.toMinutes()).getSeconds() : tokenTTLDuration.getSeconds();
    final StringBuilder sb = new StringBuilder(32);
    if (daysPart > 0) {
      sb.append(daysPart).append(" days ");
    }
    if (hoursPart > 0) {
      sb.append(hoursPart).append(" hours ");
    }
    if (minutesPart > 0) {
      sb.append(minutesPart).append(" minutes ");
    }
    if (secondsPart > 0) {
      sb.append(secondsPart).append(" seconds");
    }
    return sb.toString();
  }

  private void setTokenStateServiceStatusMap() {
    if (isServerManagedTokenStateEnabled()) {
      tokenStateServiceStatusMap.put(TSS_STATUS_IS_MANAGEMENT_ENABLED, "true");
      final GatewayConfig config = (GatewayConfig) request.getServletContext().getAttribute(GatewayConfig.GATEWAY_CONFIG_ATTRIBUTE);
      final String configuredTokenStateServiceImpl = config.getServiceParameter(ServiceType.TOKEN_STATE_SERVICE.getShortName(), "impl");
      final String configuredTokenServiceName = StringUtils.isBlank(configuredTokenStateServiceImpl) ? ""
          : configuredTokenStateServiceImpl.substring(configuredTokenStateServiceImpl.lastIndexOf('.') + 1);
      final String actualTokenStateServiceImpl = tokenStateService.getClass().getCanonicalName();
      final String actualTokenServiceName = actualTokenStateServiceImpl.substring(actualTokenStateServiceImpl.lastIndexOf('.') + 1);
      tokenStateServiceStatusMap.put(TSS_STATUS_CONFIFURED_BACKEND, configuredTokenServiceName);
      tokenStateServiceStatusMap.put(TSS_STATUS_ACTUAL_BACKEND, actualTokenServiceName);
      populateAllowedTokenStateBackendForTokenGenApp(actualTokenServiceName);
      tokenStateServiceStatusMap.put(TSS_MAXIMUM_LIFETIME_SECONDS, String.valueOf(tokenTTL == -1 ? tokenTTL : (tokenTTL / 1000)));
      tokenStateServiceStatusMap.put(TSS_MAXIMUM_LIFETIME_TEXT, tokenTTLAsText);
    } else {
      tokenStateServiceStatusMap.put(TSS_STATUS_IS_MANAGEMENT_ENABLED, "false");
    }
    final String lifespanInputEnabledValue = context.getInitParameter(LIFESPAN_INPUT_ENABLED_PARAM);
    final Boolean lifespanInputEnabled = lifespanInputEnabledValue == null ? Boolean.TRUE : Boolean.parseBoolean(lifespanInputEnabledValue);
    tokenStateServiceStatusMap.put(LIFESPAN_INPUT_ENABLED_TEXT, lifespanInputEnabled.toString());
  }

  private void populateAllowedTokenStateBackendForTokenGenApp(final String actualTokenServiceName) {
    tokenStateServiceStatusMap.put(TSS_ALLOWED_BACKEND_FOR_TOKENGEN, "false");
    final String allowedTssBackends = context.getInitParameter(TOKEN_EXP_TOKENGEN_ALLOWED_TSS_BACKENDS);
    if (allowedTssBackends != null && !allowedTssBackends.isEmpty()) {
      for (String allowedTssBackend : allowedTssBackends.split(",")) {
        if (allowedTssBackend.trim().equals(actualTokenServiceName)) {
          tokenStateServiceStatusMap.put(TSS_ALLOWED_BACKEND_FOR_TOKENGEN, "true");
          break;
        }
      }
    } else {
      //if there is no custom configuration in the topology, then we allow keystore and DB back-end for the tokengen application
      if ("AliasBasedTokenStateService".equals(actualTokenServiceName) || "JDBCTokenStateService".equals(actualTokenServiceName)) {
        tokenStateServiceStatusMap.put(TSS_ALLOWED_BACKEND_FOR_TOKENGEN, "true");
      }
    }
  }

  private void setSignatureAlogrithm() throws AliasServiceException, KeyLengthException {
    final String configuredSigAlg = context.getInitParameter(TOKEN_SIG_ALG);
    final GatewayConfig config = (GatewayConfig) request.getServletContext().getAttribute(GatewayConfig.GATEWAY_CONFIG_ATTRIBUTE);
    final GatewayServices services = (GatewayServices) request.getServletContext().getAttribute(GatewayServices.GATEWAY_SERVICES_ATTRIBUTE);
    AliasService aliasService = services.getService(ServiceType.ALIAS_SERVICE);
    signatureAlgorithm = TokenUtils.getSignatureAlgorithm(configuredSigAlg, aliasService, config.getSigningKeystoreName());
    char[] hmacSecret = aliasService.getPasswordFromAliasForGateway(TokenUtils.SIGNING_HMAC_SECRET_ALIAS);
    if (hmacSecret != null && !isAlgCompatibleWithSecret(signatureAlgorithm, hmacSecret)) {
      throw new KeyLengthException(JWSAlgorithm.parse(signatureAlgorithm));
    }
  }

  private boolean isAlgCompatibleWithSecret(String algName, char[] secret) {
    return MACSigner.getCompatibleAlgorithms(ByteUtils.bitLength(secret.length))
            .contains(JWSAlgorithm.parse(algName));
  }

  private boolean isServerManagedTokenStateEnabled() {
    boolean isServerManaged;

    // First, check for explicit service-level configuration
    String serviceParamValue = context.getInitParameter(TokenStateService.CONFIG_SERVER_MANAGED);

    // If there is no service-level configuration
    if (serviceParamValue == null || serviceParamValue.isEmpty()) {
      // Fall back to the gateway-level default
      GatewayConfig config = (GatewayConfig) context.getAttribute(GatewayConfig.GATEWAY_CONFIG_ATTRIBUTE);
      isServerManaged = (config != null) && config.isServerManagedTokenStateEnabled();
    } else {
      // Otherwise, apply the service-level configuration
      isServerManaged = Boolean.valueOf(serviceParamValue);
    }

    return isServerManaged;
  }

  @GET
  @Produces({APPLICATION_JSON, APPLICATION_XML})
  public Response doGet() {
    return getAuthenticationToken();
  }

  @POST
  @Produces({APPLICATION_JSON, APPLICATION_XML})
  public Response doPost() {
    return getAuthenticationToken();
  }

  @GET
  @Path(GET_USER_TOKENS)
  @Produces({APPLICATION_JSON, APPLICATION_XML})
  public Response getUserTokens(@Context UriInfo uriInfo) {
    if (tokenStateService == null) {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity("{\n  \"error\": \"Token management is not configured\"\n}\n").build();
    } else {
      if (uriInfo == null) {
        throw new IllegalArgumentException("URI info cannot be NULL.");
      }
      final Map<String, String> metadataMap = new HashMap<>();
      uriInfo.getQueryParameters().entrySet().forEach(entry -> {
        if (entry.getKey().startsWith(METADATA_QUERY_PARAM_PREFIX)) {
          String metadataName = entry.getKey().substring(METADATA_QUERY_PARAM_PREFIX.length());
          metadataMap.put(metadataName, entry.getValue().get(0));
        }
      });

      final String userName = uriInfo.getQueryParameters().getFirst("userName");
      final String createdBy = uriInfo.getQueryParameters().getFirst("createdBy");
      final Collection<KnoxToken> userTokens = createdBy == null ? tokenStateService.getTokens(userName) : tokenStateService.getDoAsTokens(createdBy);
      final Collection<KnoxToken> tokens = new TreeSet<>();
      if (metadataMap.isEmpty()) {
        tokens.addAll(userTokens);
      } else {
        userTokens.forEach(knoxToken -> {
          for (Map.Entry<String, String> entry : metadataMap.entrySet()) {
            if (StringUtils.isBlank(entry.getValue()) || "*".equals(entry.getValue())) {
              // we should only filter tokens by metadata name
              if (knoxToken.hasMetadata(entry.getKey())) {
                tokens.add(knoxToken);
              }
            } else {
              // metadata value should also match
              if (entry.getValue().equals(knoxToken.getMetadataValue(entry.getKey()))) {
                tokens.add(knoxToken);
              }
            }
          }
        });
      }
      return Response.status(Response.Status.OK).entity(JsonUtils.renderAsJsonString(Collections.singletonMap("tokens", tokens))).build();
    }
  }

  @GET
  @Path(GET_TSS_STATUS_PATH)
  @Produces({ APPLICATION_JSON })
  public Response getTokenStateServiceStatus() {
    return Response.status(Response.Status.OK).entity(JsonUtils.renderAsJsonString(tokenStateServiceStatusMap)).build();
  }

  @PUT
  @Path(RENEW_PATH)
  @Produces({APPLICATION_JSON})
  public Response renew(String token) {
    Response resp;

    long expiration = 0;

    String          error       = "";
    ErrorCode       errorCode   = ErrorCode.UNKNOWN;
    Response.Status errorStatus = Response.Status.BAD_REQUEST;

    if (tokenStateService == null) {
      // If the token state service is disabled, then return the expiration from the specified token
      try {
        JWTToken jwt = new JWTToken(token);
        log.renewalDisabled(getTopologyName(),
                            Tokens.getTokenDisplayText(token),
                            Tokens.getTokenIDDisplayText(TokenUtils.getTokenId(jwt)));
        expiration = Long.parseLong(jwt.getExpires());
      } catch (ParseException e) {
        log.invalidToken(getTopologyName(), Tokens.getTokenDisplayText(token), e);
        error = safeGetMessage(e);
        errorCode = ErrorCode.INVALID_TOKEN;
      } catch (Exception e) {
        error = safeGetMessage(e);
        errorCode = ErrorCode.INTERNAL_ERROR;
      }
    } else {
      String renewer = SubjectUtils.getCurrentEffectivePrincipalName();
      if (allowedRenewers.contains(renewer)) {
        try {
          JWTToken jwt = new JWTToken(token);
          // If renewal fails, it should be an exception
          expiration = tokenStateService.renewToken(jwt,
                                                    renewInterval.orElse(tokenStateService.getDefaultRenewInterval()));
          log.renewedToken(getTopologyName(),
                           Tokens.getTokenDisplayText(token),
                           Tokens.getTokenIDDisplayText(TokenUtils.getTokenId(jwt)),
                           renewer);
        } catch (ParseException e) {
          log.invalidToken(getTopologyName(), Tokens.getTokenDisplayText(token), e);
          errorCode = ErrorCode.INVALID_TOKEN;
          error = safeGetMessage(e);
        } catch (Exception e) {
          error = safeGetMessage(e);
          errorCode = ErrorCode.INTERNAL_ERROR;
        }
      } else {
        errorStatus = Response.Status.FORBIDDEN;
        error = "Caller (" + renewer + ") not authorized to renew tokens.";
        errorCode = ErrorCode.UNAUTHORIZED;
      }
    }

    if(error.isEmpty()) {
      resp =  Response.status(Response.Status.OK)
                      .entity("{\n  \"renewed\": \"true\",\n  \"expires\": \"" + expiration + "\"\n}\n")
                      .build();
    } else {
      log.badRenewalRequest(getTopologyName(), Tokens.getTokenDisplayText(token), error);
      resp = Response.status(errorStatus)
                     .entity("{\n  \"renewed\": \"false\",\n  \"error\": \"" + error + "\",\n  \"code\": " + errorCode.toInt() + "\n}\n")
                     .build();
    }

    return resp;
  }

  @DELETE
  @Path(REVOKE_PATH)
  @Produces({APPLICATION_JSON})
  public Response revoke(String token) {
    Response resp;

    String          error       = "";
    ErrorCode       errorCode   = ErrorCode.UNKNOWN;
    Response.Status errorStatus = Response.Status.BAD_REQUEST;

    if (tokenStateService == null) {
      error = "Token revocation support is not configured";
      errorCode = ErrorCode.CONFIGURATION_ERROR;
    } else {
      try {
        final String revoker = SubjectUtils.getCurrentEffectivePrincipalName();
        final String tokenId = getTokenId(token);
        if (triesToRevokeOwnToken(tokenId, revoker) || allowedRenewers.contains(revoker)) {
            tokenStateService.revokeToken(tokenId);
            log.revokedToken(getTopologyName(),
                Tokens.getTokenDisplayText(token),
                Tokens.getTokenIDDisplayText(tokenId),
                revoker);
        } else {
          errorStatus = Response.Status.FORBIDDEN;
          error = "Caller (" + revoker + ") not authorized to revoke tokens.";
          errorCode = ErrorCode.UNAUTHORIZED;
        }
      } catch (ParseException e) {
        log.invalidToken(getTopologyName(), Tokens.getTokenDisplayText(token), e);
        error = safeGetMessage(e);
        errorCode = ErrorCode.INVALID_TOKEN;
      } catch (UnknownTokenException e) {
        error = safeGetMessage(e);
        errorCode = ErrorCode.UNKNOWN_TOKEN;
      }
    }

    if (error.isEmpty()) {
      resp =  Response.status(Response.Status.OK)
                      .entity("{\n  \"revoked\": \"true\"\n}\n")
                      .build();
    } else {
      log.badRevocationRequest(getTopologyName(), Tokens.getTokenDisplayText(token), error);
      resp = Response.status(errorStatus)
                     .entity("{\n  \"revoked\": \"false\",\n  \"error\": \"" + error + "\",\n  \"code\": " + errorCode.toInt() + "\n}\n")
                     .build();
    }

    return resp;
  }

  private boolean triesToRevokeOwnToken(String tokenId, String revoker) throws UnknownTokenException {
    final TokenMetadata metadata = tokenStateService.getTokenMetadata(tokenId);
    final String tokenUserName = metadata == null ? "" : metadata.getUserName();
    return StringUtils.isNotBlank(revoker) && revoker.equals(tokenUserName);
  }

  /*
   * If the supplied 'token' conforms the UUID string representation, we consider
   * that as the token ID; otherwise we expect that 'token' is the entire JWT and
   * we get the token ID from it
   */
  private String getTokenId(String token) throws ParseException {
    try {
      UUID.fromString(token);
      return token;
    } catch (IllegalArgumentException e) {
      //NOP: the supplied token is not a UUID, we expect the entire JWT
    }
    final JWTToken jwt = new JWTToken(token);
    return TokenUtils.getTokenId(jwt);
  }

  @PUT
  @Path(ENABLE_PATH)
  @Produces({ APPLICATION_JSON })
  public Response enable(String tokenId) {
    return setTokenEnabledFlag(tokenId, true);
  }

  @PUT
  @Path(DISABLE_PATH)
  @Produces({ APPLICATION_JSON })
  public Response disable(String tokenId) {
    return setTokenEnabledFlag(tokenId, false);
  }

  private Response setTokenEnabledFlag(String tokenId, boolean enabled) {
    String error = "";
    ErrorCode errorCode = ErrorCode.UNKNOWN;
    if (tokenStateService == null) {
      error = "Unable to " + (enabled ? "enable" : "disable") + " tokens because token management is not configured";
      errorCode = ErrorCode.CONFIGURATION_ERROR;
    } else {
      try {
        final TokenMetadata tokenMetadata = tokenStateService.getTokenMetadata(tokenId);
        if (enabled && tokenMetadata.isEnabled()) {
          error = "Token is already enabled";
          errorCode = ErrorCode.ALREADY_ENABLED;
        } else if (!enabled && !tokenMetadata.isEnabled()) {
          error = "Token is already disabled";
          errorCode = ErrorCode.ALREADY_DISABLED;
        } else {
          tokenMetadata.setEnabled(enabled);
          tokenStateService.addMetadata(tokenId, tokenMetadata);
        }
      } catch (UnknownTokenException e) {
        error = safeGetMessage(e);
        errorCode = ErrorCode.UNKNOWN_TOKEN;
      }
    }
    if (error.isEmpty()) {
      return Response.status(Response.Status.OK).entity("{\n  \"setEnabledFlag\": \"true\",\n  \"isEnabled\": \"" + enabled + "\"\n}\n").build();
    } else {
      log.badSetEnabledFlagRequest(getTopologyName(), Tokens.getTokenIDDisplayText(tokenId), error);
      return Response.status(Response.Status.BAD_REQUEST).entity("{\n  \"setEnabledFlag\": \"false\",\n  \"error\": \"" + error + "\",\n  \"code\": " + errorCode.toInt() + "\n}\n").build();
    }
  }

  private X509Certificate extractCertificate(HttpServletRequest req) {
    X509Certificate[] certs = (X509Certificate[]) req.getAttribute("javax.servlet.request.X509Certificate");
    if (null != certs && certs.length > 0) {
      return certs[0];
    }
    return null;
  }

  private Response getAuthenticationToken() {
    if (clientCertRequired) {
      X509Certificate cert = extractCertificate(request);
      if (cert != null) {
        if (!allowedDNs.contains(cert.getSubjectDN().getName().replaceAll("\\s+", ""))) {
          return Response.status(Response.Status.FORBIDDEN)
                         .entity("{ \"Unable to get token - untrusted client cert.\" }")
                         .build();
        }
      } else {
        return Response.status(Response.Status.FORBIDDEN)
                       .entity("{ \"Unable to get token - client cert required.\" }")
                       .build();
      }
    }
    GatewayServices services = (GatewayServices) request.getServletContext()
        .getAttribute(GatewayServices.GATEWAY_SERVICES_ATTRIBUTE);

    JWTokenAuthority ts = services.getService(ServiceType.TOKEN_SERVICE);

    String userName = request.getUserPrincipal().getName();
    String createdBy = null;
    // checking the doAs user only makes sense if tokens are managed (this is where we store the userName information)
    if (tokenStateService != null) {
      final String doAsUser = request.getParameter(QUERY_PARAMETER_DOAS);
      if (doAsUser != null && !doAsUser.equals(userName)) {
        try {
          //this call will authorize the doAs request
          AuthFilterUtils.authorizeImpersonationRequest(request, doAsUser);
          createdBy = userName;
          userName = doAsUser;
        } catch (AuthorizationException e) {
          return Response.status(Response.Status.FORBIDDEN).entity("{ \"" + e.getMessage() + "\" }").build();
        }
      }
    }

    long expires = getExpiry();

    if (endpointPublicCert == null) {
      // acquire PEM for gateway identity of this gateway instance
      KeystoreService ks = services.getService(ServiceType.KEYSTORE_SERVICE);
      if (ks != null) {
        try {
          Certificate cert = ks.getCertificateForGateway();
          byte[] bytes = cert.getEncoded();
          endpointPublicCert = Base64.encodeBase64String(bytes);
        } catch (KeyStoreException | KeystoreServiceException | CertificateEncodingException e) {
          // assuming that certs will be properly provisioned across all clients
          log.unableToAcquireCertForEndpointClients(e);
        }
      }
    }

    String jku = null;
    /* remove .../token and replace it with ..../jwks.json */
    final int idx = request.getRequestURL().lastIndexOf("/");
    if(idx > 1) {
      jku = request.getRequestURL().substring(0, idx) + JWKSResource.JWKS_PATH;
    }

    if (tokenStateService != null) {
      if (tokenLimitPerUser != -1) { // if -1 => unlimited tokens for all users
        final Collection<KnoxToken> userTokens = tokenStateService.getTokens(userName);
        if (userTokens.size() >= tokenLimitPerUser) {
          log.tokenLimitExceeded(userName);
          if (UserLimitExceededAction.RETURN_ERROR == userLimitExceededAction) {
            return Response.status(Response.Status.FORBIDDEN).entity("{ \"Unable to get token - token limit exceeded.\" }").build();
          } else {
            // userTokens is an ordered collection (by issue time) -> the first element is the oldest one
            final String oldestTokenId = userTokens.iterator().next().getTokenId();
            log.generalInfoMessage(String.format(Locale.getDefault(), "Revoking %s's oldest token %s ...", userName, Tokens.getTokenIDDisplayText(oldestTokenId)));
            revoke(oldestTokenId);
           }
        }
      }
    }

    try {
      final boolean managedToken = tokenStateService != null;
      JWT token;
      JWTokenAttributes jwtAttributes;
      final JWTokenAttributesBuilder jwtAttributesBuilder = new JWTokenAttributesBuilder();
      jwtAttributesBuilder
          .setUserName(userName)
          .setAlgorithm(signatureAlgorithm)
          .setExpires(expires)
          .setManaged(managedToken)
          .setJku(jku)
          .setType(tokenType);
      if (!targetAudiences.isEmpty()) {
        jwtAttributesBuilder.setAudiences(targetAudiences);
      }

      jwtAttributes = jwtAttributesBuilder.build();
      token = ts.issueToken(jwtAttributes);

      if (token != null) {
        String accessToken = token.toString();
        String tokenId = TokenUtils.getTokenId(token);
        log.issuedToken(getTopologyName(), Tokens.getTokenDisplayText(accessToken), Tokens.getTokenIDDisplayText(tokenId));

        final HashMap<String, Object> map = new HashMap<>();
        map.put(ACCESS_TOKEN, accessToken);
        map.put(TOKEN_ID, tokenId);
        map.put(MANAGED_TOKEN, String.valueOf(managedToken));
        map.put(TOKEN_TYPE, BEARER);
        map.put(EXPIRES_IN, expires);
        if (tokenTargetUrl != null) {
          map.put(TARGET_URL, tokenTargetUrl);
        }
        if (tokenClientDataMap != null) {
          map.putAll(tokenClientDataMap);
        }
        if (endpointPublicCert != null) {
          map.put(ENDPOINT_PUBLIC_CERT, endpointPublicCert);
        }
        final String passcode = UUID.randomUUID().toString();
        map.put(PASSCODE, generatePasscodeField(tokenId, passcode));

        String jsonResponse = JsonUtils.renderAsJsonString(map);

        // Optional token store service persistence
        if (tokenStateService != null) {
          final long issueTime = System.currentTimeMillis();
          tokenStateService.addToken(tokenId,
                                     issueTime,
                                     expires,
                                     maxTokenLifetime.orElse(tokenStateService.getDefaultMaxLifetimeDuration()));
          final String comment = request.getParameter(COMMENT);
          final TokenMetadata tokenMetadata = new TokenMetadata(userName, StringUtils.isBlank(comment) ? null : comment);
          tokenMetadata.setPasscode(tokenMAC.hash(tokenId, issueTime, userName, passcode));
          addArbitraryTokenMetadata(tokenMetadata);
          if (createdBy != null) {
            tokenMetadata.setCreatedBy(createdBy);
          }
          tokenStateService.addMetadata(tokenId, tokenMetadata);
          log.storedToken(getTopologyName(), Tokens.getTokenDisplayText(accessToken), Tokens.getTokenIDDisplayText(tokenId));
        }

        return Response.ok().entity(jsonResponse).build();
      } else {
        return Response.serverError().build();
      }
    } catch (TokenServiceException e) {
      log.unableToIssueToken(e);
    }
    return Response.ok().entity("{ \"Unable to acquire token.\" }").build();
  }

  private void addArbitraryTokenMetadata(TokenMetadata tokenMetadata) {
    final Enumeration<String> paramNames = request.getParameterNames();
    while (paramNames.hasMoreElements()) {
      final String paramName = paramNames.nextElement();
      if (paramName.startsWith(METADATA_QUERY_PARAM_PREFIX)) {
        final String metadataName = paramName.substring(METADATA_QUERY_PARAM_PREFIX.length());
        final String metadataValue = request.getParameter(paramName);
        tokenMetadata.add(metadataName, metadataValue);
      }
    }
  }

  private String generatePasscodeField(String tokenId, String passcode) {
    final String base64TokenIdPasscode = Base64.encodeBase64String(tokenId.getBytes(StandardCharsets.UTF_8)) + "::" + Base64.encodeBase64String(passcode.getBytes(StandardCharsets.UTF_8));
    return Base64.encodeBase64String(base64TokenIdPasscode.getBytes(StandardCharsets.UTF_8));
  }

  void addClientDataToMap(String[] tokenClientData,
      Map<String,Object> map) {
    String[] kv;
    for (String tokenClientDatum : tokenClientData) {
      //client data value may contain the '=' itself. For instance "homepage_url=homepage/home?profile=token&amp;topologies=sandbox"
      kv = tokenClientDatum.split("=", 2);
      if (kv.length == 2) {
        map.put(kv[0], kv[1]);
      }
    }
  }

  private long getExpiry() {
    long expiry = 0L;
    long millis = tokenTTL;

    String lifetimeStr = request.getParameter(LIFESPAN);
    if (lifetimeStr == null || lifetimeStr.isEmpty()) {
      if (tokenTTL == -1) {
        return -1;
      }
    }
    else {
      try {
        long lifetime = Duration.parse(lifetimeStr).toMillis();
        if (tokenTTL == -1) {
          // if TTL is set to -1 the topology owner grants unlimited lifetime therefore no additional check is needed on lifespan
          millis = lifetime;
        } else if (lifetime <= tokenTTL) {
          //this is expected due to security reasons: the configured TTL acts as an upper limit regardless of the supplied lifespan
          millis = lifetime;
        }
      }
      catch (DateTimeParseException e) {
        log.invalidLifetimeValue(lifetimeStr);
      }
    }
    expiry = System.currentTimeMillis() + millis;

    return expiry;
  }

  private String getTopologyName() {
    return (String) context.getAttribute("org.apache.knox.gateway.gateway.cluster");
  }

  /**
   * Safely get the message from the specified Throwable.
   *
   * @param t A Throwable
   * @return The result of t.getMessage(), or &quot;null&quot; if that result is null.
   */
  private String safeGetMessage(Throwable t) {
    String message = t.getMessage();
    return message != null ? message : "null";
  }
}
