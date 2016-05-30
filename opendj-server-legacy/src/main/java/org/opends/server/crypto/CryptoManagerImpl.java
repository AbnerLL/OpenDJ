/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2006-2010 Sun Microsystems, Inc.
 * Portions Copyright 2009 Parametric Technology Corporation (PTC)
 * Portions Copyright 2011-2016 ForgeRock AS.
 */
package org.opends.server.crypto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import net.jcip.annotations.GuardedBy;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.forgerock.opendj.config.server.ConfigChangeResult;
import org.forgerock.opendj.config.server.ConfigException;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.DN;
import org.forgerock.opendj.ldap.ModificationType;
import org.forgerock.opendj.ldap.RDN;
import org.forgerock.opendj.ldap.ResultCode;
import org.forgerock.opendj.ldap.SearchScope;
import org.forgerock.opendj.ldap.schema.AttributeType;
import org.forgerock.util.Reject;
import org.opends.admin.ads.ADSContext;
import org.forgerock.opendj.config.server.ConfigurationChangeListener;
import org.forgerock.opendj.server.config.server.CryptoManagerCfg;
import org.opends.server.api.Backend;
import org.opends.server.backends.TrustStoreBackend;
import org.opends.server.core.AddOperation;
import org.opends.server.core.DirectoryServer;
import org.opends.server.core.ModifyOperation;
import org.opends.server.core.ServerContext;
import org.opends.server.protocols.internal.InternalClientConnection;
import org.opends.server.protocols.internal.InternalSearchOperation;
import org.opends.server.protocols.internal.SearchRequest;
import org.opends.server.protocols.ldap.ExtendedRequestProtocolOp;
import org.opends.server.protocols.ldap.ExtendedResponseProtocolOp;
import org.opends.server.protocols.ldap.LDAPMessage;
import org.opends.server.protocols.ldap.LDAPResultCode;
import org.opends.server.tools.LDAPConnection;
import org.opends.server.tools.LDAPConnectionOptions;
import org.opends.server.tools.LDAPReader;
import org.opends.server.tools.LDAPWriter;
import org.opends.server.types.Attribute;
import org.opends.server.types.AttributeBuilder;
import org.opends.server.types.Attributes;
import org.opends.server.types.Control;
import org.opends.server.types.CryptoManager;
import org.opends.server.types.CryptoManagerException;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.Entry;
import org.opends.server.types.IdentifiedException;
import org.opends.server.types.InitializationException;
import org.opends.server.types.Modification;
import org.forgerock.opendj.ldap.schema.ObjectClass;
import org.opends.server.types.SearchResultEntry;
import org.opends.server.util.Base64;
import org.opends.server.util.SelectableCertificateKeyManager;
import org.opends.server.util.ServerConstants;
import org.opends.server.util.StaticUtils;

import static org.opends.messages.CoreMessages.*;
import static org.opends.server.config.ConfigConstants.*;
import static org.opends.server.protocols.internal.InternalClientConnection.*;
import static org.opends.server.protocols.internal.Requests.*;
import static org.opends.server.util.CollectionUtils.*;
import static org.opends.server.util.ServerConstants.*;
import static org.opends.server.util.StaticUtils.*;

/**
 This class implements the Directory Server cryptographic framework,
 which is described in the
 <a href="https://www.opends.org/wiki//page/TheCryptoManager">
 CrytpoManager design document</a>.  {@code CryptoManager} implements
 inter-OpenDJ-instance authentication and authorization using the
 ADS-based truststore, and secret key distribution. The interface also
 provides methods for hashing, encryption, and other kinds of
 cryptographic operations.
 <p>
 Note that it also contains methods for compressing and uncompressing
 data: while these are not strictly cryptographic operations, there
 are a lot of similarities and it is conceivable at some point that
 accelerated compression may be available just as it is for
 cryptographic operations.
 <p>
 Other components of CryptoManager:
 @see org.opends.server.crypto.CryptoManagerSync
 @see org.opends.server.crypto.GetSymmetricKeyExtendedOperation
 */
public class CryptoManagerImpl implements ConfigurationChangeListener<CryptoManagerCfg>, CryptoManager
{
  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();

  /** Various schema element references. */
  private static AttributeType attrKeyID;
  private static AttributeType attrPublicKeyCertificate;
  private static AttributeType attrTransformation;
  private static AttributeType attrMacAlgorithm;
  private static AttributeType attrSymmetricKey;
  private static AttributeType attrInitVectorLength;
  private static AttributeType attrKeyLength;
  private static AttributeType attrCompromisedTime;
  private static ObjectClass   ocCertRequest;
  private static ObjectClass   ocInstanceKey;
  private static ObjectClass   ocCipherKey;
  private static ObjectClass   ocMacKey;

  /** The DN of the local truststore backend. */
  private static DN localTruststoreDN;

  /** The DN of the ADS instance keys container. */
  private static DN instanceKeysDN;

  /** The DN of the ADS secret keys container. */
  private static DN secretKeysDN;

  /** The DN of the ADS servers container. */
  private static DN serversDN;

  /** Indicates whether the schema references have been initialized. */
  private static boolean schemaInitDone;

  /** The secure random number generator used for key generation, initialization vector PRNG seed. */
  private static final SecureRandom secureRandom = new SecureRandom();

  /**
   * The first byte in any ciphertext produced by CryptoManager is the prologue
   * version. At present, this constant is both the version written and the
   * expected version. If a new version is introduced (e.g., to allow embedding
   * the HMAC key identifier and signature in a signed backup) the prologue
   * version will likely need to be configurable at the granularity of the
   * CryptoManager client (e.g., password encryption might use version 1, while
   * signed backups might use version 2.
   */
  private static final int CIPHERTEXT_PROLOGUE_VERSION = 1 ;

  private final Lock cipherKeyEntryLock = new ReentrantLock();
  /**
   * The map from encryption key ID to CipherKeyEntry (cache). The cache is
   * accessed by methods that request by ID, publish, and import keys.
   * It contains all keys, even compromised, to be able to access old data.
   */
  @GuardedBy("cipherKeyEntryLock")
  private final Map<KeyEntryID, CipherKeyEntry> cipherKeyEntryCache = new ConcurrentHashMap<>();
  /**
   * Keys imported or generated to use for encryption, mapped by transformation/key length.
   * Only one cipher key per transformation/key length (used as a key for the map, for example
   * "AES/CBC/PKCS5Padding/128") is recorded, the last in temporal order to be imported or
   * generated.
   * Cipher keys belonging to this map also belong in the cache Map, they are used as keys for
   * encrypting new data.
   *
   */
  @GuardedBy("cipherKeyEntryLock")
  private final Map<String, CipherKeyEntry> mostRecentCipherKeys = new ConcurrentHashMap<>();

  private final Lock macKeyEntryLock = new ReentrantLock();
  /**
   * The map from encryption key ID to MacKeyEntry (cache). The cache is
   * accessed by methods that request by ID, publish, and import keys.
   * It contains all keys, even compromised, to be able to access old data.
   */
  @GuardedBy("macKeyEntryLock")
  private final Map<KeyEntryID, MacKeyEntry> macKeyEntryCache = new ConcurrentHashMap<>();
  /**
   * Keys imported or generated for MAC operations, mapped by algorithm/key length.
   * Only one MAC key per algorithm/key length (used as a key for the map, for example
   * "HmacSHA1/128") is recorded, the last in temporal order to be imported or
   * generated.
   * MAC keys belonging to this map also belong in the cache Map, they are
   * used for computing new MAC digests.
   */
  @GuardedBy("macKeyEntryLock")
  private final Map<String, MacKeyEntry> mostRecentMacKeys = new ConcurrentHashMap<>();

  /** The preferred key wrapping transformation. */
  private String preferredKeyWrappingTransformation;


  // TODO: Move the following configuration to backup or backend configuration.
  // TODO: https://opends.dev.java.net/issues/show_bug.cgi?id=2472

  /** The preferred message digest algorithm for the Directory Server. */
  private String preferredDigestAlgorithm;

  /** The preferred cipher for the Directory Server. */
  private String preferredCipherTransformation;

  /** The preferred key length for the preferred cipher. */
  private int preferredCipherTransformationKeyLengthBits;

  /** The preferred MAC algorithm for the Directory Server. */
  private String preferredMACAlgorithm;

  /** The preferred key length for the preferred MAC algorithm. */
  private int preferredMACAlgorithmKeyLengthBits;


  // TODO: Move the following configuration to replication configuration.
  // TODO: https://opends.dev.java.net/issues/show_bug.cgi?id=2473

  /** The names of the local certificates to use for SSL. */
  private final SortedSet<String> sslCertNicknames;

  /** Whether replication sessions use SSL encryption. */
  private final boolean sslEncryption;

  /** The set of SSL protocols enabled or null for the default set. */
  private final SortedSet<String> sslProtocols;

  /** The set of SSL cipher suites enabled or null for the default set. */
  private final SortedSet<String> sslCipherSuites;

  private final ServerContext serverContext;

  /**
   * Creates a new instance of this crypto manager object from a given
   * configuration, plus some static member initialization.
   *
   * @param serverContext
   *            The server context.
   * @param config
   *          The configuration of this crypto manager.
   * @throws ConfigException
   *           If a problem occurs while creating this {@code CryptoManager}
   *           that is a result of a problem in the configuration.
   * @throws InitializationException
   *           If a problem occurs while creating this {@code CryptoManager}
   *           that is not the result of a problem in the configuration.
   */
  public CryptoManagerImpl(ServerContext serverContext, CryptoManagerCfg config)
         throws ConfigException, InitializationException {
    this.serverContext = serverContext;
    if (!schemaInitDone) {
      // Initialize various schema references.
      attrKeyID = DirectoryServer.getAttributeType(ATTR_CRYPTO_KEY_ID);
      attrPublicKeyCertificate = DirectoryServer.getAttributeType(ATTR_CRYPTO_PUBLIC_KEY_CERTIFICATE);
      attrTransformation = DirectoryServer.getAttributeType(ATTR_CRYPTO_CIPHER_TRANSFORMATION_NAME);
      attrMacAlgorithm = DirectoryServer.getAttributeType(ATTR_CRYPTO_MAC_ALGORITHM_NAME);
      attrSymmetricKey = DirectoryServer.getAttributeType(ATTR_CRYPTO_SYMMETRIC_KEY);
      attrInitVectorLength = DirectoryServer.getAttributeType(ATTR_CRYPTO_INIT_VECTOR_LENGTH_BITS);
      attrKeyLength = DirectoryServer.getAttributeType(ATTR_CRYPTO_KEY_LENGTH_BITS);
      attrCompromisedTime = DirectoryServer.getAttributeType(ATTR_CRYPTO_KEY_COMPROMISED_TIME);
      ocCertRequest = DirectoryServer.getObjectClass("ds-cfg-self-signed-cert-request"); // TODO: ConfigConstants
      ocInstanceKey = DirectoryServer.getObjectClass(OC_CRYPTO_INSTANCE_KEY);
      ocCipherKey = DirectoryServer.getObjectClass(OC_CRYPTO_CIPHER_KEY);
      ocMacKey = DirectoryServer.getObjectClass(OC_CRYPTO_MAC_KEY);

      localTruststoreDN = DN.valueOf(DN_TRUST_STORE_ROOT);
      DN adminSuffixDN = DN.valueOf(ADSContext.getAdministrationSuffixDN());
      instanceKeysDN = adminSuffixDN.child(DN.valueOf("cn=instance keys"));
      secretKeysDN = adminSuffixDN.child(DN.valueOf("cn=secret keys"));
      serversDN = adminSuffixDN.child(DN.valueOf("cn=Servers"));

      schemaInitDone = true;
    }

    // CryptoMangager crypto config parameters.
    List<LocalizableMessage> why = new LinkedList<>();
    if (! isConfigurationChangeAcceptable(config, why)) {
      throw new InitializationException(why.get(0));
    }
    applyConfigurationChange(config);

    // Secure replication related...
    sslCertNicknames = config.getSSLCertNickname();
    sslEncryption   = config.isSSLEncryption();
    sslProtocols    = config.getSSLProtocol();
    sslCipherSuites = config.getSSLCipherSuite();

    // Register as a configuration change listener.
    config.addChangeListener(this);
  }

  @Override
  public boolean isConfigurationChangeAcceptable(
       CryptoManagerCfg cfg,
       List<LocalizableMessage> unacceptableReasons)
  {
    // Acceptable until we find an error.
    boolean isAcceptable = true;

    // Requested digest validation.
    String requestedDigestAlgorithm =
         cfg.getDigestAlgorithm();
    if (! requestedDigestAlgorithm.equals(this.preferredDigestAlgorithm))
    {
      try{
        MessageDigest.getInstance(requestedDigestAlgorithm);
      }
      catch (Exception ex) {
        logger.traceException(ex);
        unacceptableReasons.add(
             ERR_CRYPTOMGR_CANNOT_GET_REQUESTED_DIGEST.get(
                  requestedDigestAlgorithm, getExceptionMessage(ex)));
        isAcceptable = false;
      }
    }

    // Requested encryption cipher validation.
    String requestedCipherTransformation =
         cfg.getCipherTransformation();
    Integer requestedCipherTransformationKeyLengthBits =
         cfg.getCipherKeyLength();
    if (! requestedCipherTransformation.equals(
            this.preferredCipherTransformation) ||
        requestedCipherTransformationKeyLengthBits !=
            this.preferredCipherTransformationKeyLengthBits) {
      if (3 != requestedCipherTransformation.split("/",0).length) {
        unacceptableReasons.add(
                ERR_CRYPTOMGR_FULL_CIPHER_TRANSFORMATION_REQUIRED.get(
                        requestedCipherTransformation));
        isAcceptable = false;
      }
      else {
        try {
          CipherKeyEntry.generateKeyEntry(null,
                  requestedCipherTransformation,
                  requestedCipherTransformationKeyLengthBits);
        }
        catch (Exception ex) {
          logger.traceException(ex);
          unacceptableReasons.add(
             ERR_CRYPTOMGR_CANNOT_GET_REQUESTED_ENCRYPTION_CIPHER.get(
                     requestedCipherTransformation, getExceptionMessage(ex)));
          isAcceptable = false;
        }
      }
    }

    // Requested MAC algorithm validation.
    String requestedMACAlgorithm = cfg.getMacAlgorithm();
    Integer requestedMACAlgorithmKeyLengthBits =
         cfg.getMacKeyLength();
    if (!requestedMACAlgorithm.equals(this.preferredMACAlgorithm) ||
         requestedMACAlgorithmKeyLengthBits !=
              this.preferredMACAlgorithmKeyLengthBits)
    {
      try {
        MacKeyEntry.generateKeyEntry(
             null,
             requestedMACAlgorithm,
             requestedMACAlgorithmKeyLengthBits);
      }
      catch (Exception ex) {
        logger.traceException(ex);
        unacceptableReasons.add(
                ERR_CRYPTOMGR_CANNOT_GET_REQUESTED_MAC_ENGINE.get(
                        requestedMACAlgorithm, getExceptionMessage(ex)));
        isAcceptable = false;
      }
    }
    // Requested secret key wrapping cipher and validation. Validation
    // depends on MAC cipher for secret key.
    String requestedKeyWrappingTransformation
            = cfg.getKeyWrappingTransformation();
    if (! requestedKeyWrappingTransformation.equals(
            this.preferredKeyWrappingTransformation)) {
      if (3 != requestedKeyWrappingTransformation.split("/", 0).length) {
        unacceptableReasons.add(
                ERR_CRYPTOMGR_FULL_KEY_WRAPPING_TRANSFORMATION_REQUIRED.get(
                        requestedKeyWrappingTransformation));
        isAcceptable = false;
      }
      else {
        try {
          /* Note that the TrustStoreBackend not available at initial,
         CryptoManager configuration, hence a "dummy" certificate must be used
         to validate the choice of secret key wrapping cipher. Otherwise, call
         getInstanceKeyCertificateFromLocalTruststore() */
          final String certificateBase64 =
                "MIIB2jCCAUMCBEb7wpYwDQYJKoZIhvcNAQEEBQAwNDEbMBkGA1UEChMST3B" +
                "lbkRTIENlcnRpZmljYXRlMRUwEwYDVQQDEwwxMC4wLjI0OC4yNTEwHhcNMD" +
                "cwOTI3MTQ0NzUwWhcNMjcwOTIyMTQ0NzUwWjA0MRswGQYDVQQKExJPcGVuR" +
                "FMgQ2VydGlmaWNhdGUxFTATBgNVBAMTDDEwLjAuMjQ4LjI1MTCBnzANBgkq" +
                "hkiG9w0BAQEFAAOBjQAwgYkCgYEAnIm6ELyuNVbpaacBQ7fzHlHMmQO/CYJ" +
                "b2gPTdb9n1HLOBqh2lmLLHvt2SgBeN5TSa1PAHW8zJy9LDhpWKZvsUOIdQD" +
                "8Ula/0d/jvMEByEj/hr00P6yqgLXk+EudPgOkFXHA+IfkkOSghMooWc/L8H" +
                "nD1REdqeZuxp+ARNU+cc/ECAwEAATANBgkqhkiG9w0BAQQFAAOBgQBemyCU" +
                "jucN34MZwvzbmFHT/leUu3/cpykbGM9HL2QUX7iKvv2LJVqexhj7CLoXxZP" +
                "oNL+HHKW0vi5/7W5KwOZsPqKI2SdYV7nDqTZklm5ZP0gmIuNO6mTqBRtC2D" +
                "lplX1Iq+BrQJAmteiPtwhdZD+EIghe51CaseImjlLlY2ZK8w==";
          final byte[] certificate = Base64.decode(certificateBase64);
          final String keyID = getInstanceKeyID(certificate);
          final SecretKey macKey = MacKeyEntry.generateKeyEntry(null,
                  requestedMACAlgorithm,
                  requestedMACAlgorithmKeyLengthBits).getSecretKey();
          encodeSymmetricKeyAttribute(requestedKeyWrappingTransformation,
                  keyID, certificate, macKey);
        }
        catch (Exception ex) {
          logger.traceException(ex);
          unacceptableReasons.add(
                  ERR_CRYPTOMGR_CANNOT_GET_PREFERRED_KEY_WRAPPING_CIPHER.get(
                          getExceptionMessage(ex)));
          isAcceptable = false;
        }
      }
    }
    return isAcceptable;
  }

  @Override
  public ConfigChangeResult applyConfigurationChange(CryptoManagerCfg cfg)
  {
    preferredDigestAlgorithm = cfg.getDigestAlgorithm();
    preferredMACAlgorithm = cfg.getMacAlgorithm();
    preferredMACAlgorithmKeyLengthBits = cfg.getMacKeyLength();
    preferredCipherTransformation = cfg.getCipherTransformation();
    preferredCipherTransformationKeyLengthBits = cfg.getCipherKeyLength();
    preferredKeyWrappingTransformation = cfg.getKeyWrappingTransformation();
    return new ConfigChangeResult();
  }


  /**
   * Retrieve the ADS trust store backend.
   * @return The ADS trust store backend.
   * @throws ConfigException If the ADS trust store backend is
   *                         not configured.
   */
  private TrustStoreBackend getTrustStoreBackend()
       throws ConfigException
  {
    Backend<?> b = DirectoryServer.getBackend(ID_ADS_TRUST_STORE_BACKEND);
    if (b == null)
    {
      throw new ConfigException(ERR_CRYPTOMGR_ADS_TRUST_STORE_BACKEND_NOT_ENABLED.get(ID_ADS_TRUST_STORE_BACKEND));
    }
    if (!(b instanceof TrustStoreBackend))
    {
      throw new ConfigException(ERR_CRYPTOMGR_ADS_TRUST_STORE_BACKEND_WRONG_CLASS.get(ID_ADS_TRUST_STORE_BACKEND));
    }
    return (TrustStoreBackend)b;
  }


  /**
   * Returns this instance's instance-key public-key certificate from
   * the local keystore (i.e., from the truststore-backend and not
   * from the ADS backed keystore). If the certificate entry does not
   * yet exist in the truststore backend, the truststore is signaled
   * to initialized that entry, and the newly generated certificate
   * is then retrieved and returned. The certificate returned can never
   * be null.
   *
   * @return This instance's instance-key public-key certificate from
   * the local truststore backend.
   * @throws CryptoManagerException If the certificate cannot be
   * retrieved, or, was not able to be initialized by the trust-store.
   */
  static byte[] getInstanceKeyCertificateFromLocalTruststore()
          throws CryptoManagerException {
    // Construct the key entry DN.
    final ByteString distinguishedValue = ByteString.valueOfUtf8(ADS_CERTIFICATE_ALIAS);
    final DN entryDN = localTruststoreDN.child(new RDN(attrKeyID, distinguishedValue));
    // Construct the search filter.
    final String FILTER_OC_INSTANCE_KEY = "(objectclass=" + ocInstanceKey.getNameOrOID() + ")";
    // Construct the attribute list.
    String requestedAttribute = attrPublicKeyCertificate.getNameOrOID() + ";binary";

    // Retrieve the certificate from the entry.
    final InternalClientConnection icc = getRootConnection();
    byte[] certificate = null;
    try {
      for (int i = 0; i < 2; ++i) {
        try {
          /* If the entry does not exist in the instance's truststore
             backend, add it using a special object class that induces
             the backend to create the public-key certificate
             attribute, then repeat the search. */
          final SearchRequest request = newSearchRequest(entryDN, SearchScope.BASE_OBJECT, FILTER_OC_INSTANCE_KEY)
              .addAttribute(requestedAttribute);
          InternalSearchOperation searchOp = icc.processSearch(request);
          for (Entry e : searchOp.getSearchEntries()) {
            // attribute ds-cfg-public-key-certificate is a MUST in the schema
            certificate = e.parseAttribute(
                ATTR_CRYPTO_PUBLIC_KEY_CERTIFICATE).asByteString().toByteArray();
          }
          break;
        }
        catch (DirectoryException ex) {
          if (0 != i || ex.getResultCode() != ResultCode.NO_SUCH_OBJECT) {
            throw ex;
          }

          final Entry entry = new Entry(entryDN, null, null, null);
          entry.addObjectClass(DirectoryServer.getTopObjectClass());
          entry.addObjectClass(ocCertRequest);
          AddOperation addOperation = icc.processAdd(entry);
          if (ResultCode.SUCCESS != addOperation.getResultCode()) {
            throw new DirectoryException(
                addOperation.getResultCode(),
                ERR_CRYPTOMGR_FAILED_TO_INITIATE_INSTANCE_KEY_GENERATION.get(entry.getName()));
          }
        }
      }
    }
    catch (DirectoryException ex) {
      logger.traceException(ex);
      throw new CryptoManagerException(
            ERR_CRYPTOMGR_FAILED_TO_RETRIEVE_INSTANCE_CERTIFICATE.get(
                    entryDN, getExceptionMessage(ex)), ex);
    }
    //The certificate can never be null. The LocalizableMessage digest code that will
    //use it later throws a NPE if the certificate is null.
    if (certificate == null) {
      throw new CryptoManagerException(
          ERR_CRYPTOMGR_FAILED_INSTANCE_CERTIFICATE_NULL.get(entryDN));
    }
    return certificate;
  }


  /**
   * Return the identifier of this instance's instance-key. An
   * instance-key identifier is a hex string of the MD5 hash of an
   * instance's instance-key public-key certificate.
   * @see #getInstanceKeyID(byte[])
   * @return This instance's instance-key identifier.
   * @throws CryptoManagerException If there is a problem retrieving
   * the instance-key public-key certificate or computing its MD5
   * hash.
   */
  String getInstanceKeyID()
          throws CryptoManagerException {
    return getInstanceKeyID(
            getInstanceKeyCertificateFromLocalTruststore());
  }


  /**
   * Return the identifier of an instance's instance key. An
   * instance-key identifier is a hex string of the MD5 hash of an
   * instance's instance-key public-key certificate.
   * @see #getInstanceKeyID()
   * @param instanceKeyCertificate The instance key for which to
   * return an identifier.
   * @return The identifier of the supplied instance key.
   * @throws CryptoManagerException If there is a problem computing
   * the identifier from the instance key.
   *
   * TODO: Make package-private if ADSContextHelper can get keyID from ADS
   * TODO: suffix: Issue https://opends.dev.java.net/issues/show_bug.cgi?id=2442
   */
  public static String getInstanceKeyID(byte[] instanceKeyCertificate)
            throws CryptoManagerException {
    MessageDigest md;
    final String mdAlgorithmName = "MD5";
    try {
      md = MessageDigest.getInstance(mdAlgorithmName);
    }
    catch (NoSuchAlgorithmException ex) {
      logger.traceException(ex);
      throw new CryptoManagerException(
          ERR_CRYPTOMGR_FAILED_TO_COMPUTE_INSTANCE_KEY_IDENTIFIER.get(
                  getExceptionMessage(ex)), ex);
    }
    return StaticUtils.bytesToHexNoSpace(
         md.digest(instanceKeyCertificate));
  }

  /**
   Publishes the instance key entry in ADS, if it does not already exist.

   @throws CryptoManagerException In case there is a problem
   searching for the entry, or, if necessary, adding it.
   */
  static void publishInstanceKeyEntryInADS()
          throws CryptoManagerException {
    final byte[] instanceKeyCertificate = getInstanceKeyCertificateFromLocalTruststore();
    final String instanceKeyID = getInstanceKeyID(instanceKeyCertificate);
    // Construct the key entry DN.
    final ByteString distinguishedValue = ByteString.valueOfUtf8(instanceKeyID);
    final DN entryDN = instanceKeysDN.child(
         new RDN(attrKeyID, distinguishedValue));

    // Check for the entry. If it does not exist, create it.
    final String FILTER_OC_INSTANCE_KEY = "(objectclass=" + ocInstanceKey.getNameOrOID() + ")";
    final InternalClientConnection icc = getRootConnection();
    try {
      final SearchRequest request =
          newSearchRequest(entryDN, SearchScope.BASE_OBJECT, FILTER_OC_INSTANCE_KEY).addAttribute("dn");
      final InternalSearchOperation searchOp = icc.processSearch(request);
      if (searchOp.getSearchEntries().isEmpty()) {
        final Entry entry = new Entry(entryDN, null, null, null);
        entry.addObjectClass(DirectoryServer.getTopObjectClass());
        entry.addObjectClass(ocInstanceKey);

        // Add the key ID attribute.
        final Attribute keyIDAttr = Attributes.create(attrKeyID, distinguishedValue);
        entry.addAttribute(keyIDAttr, new ArrayList<ByteString>(0));

        // Add the public key certificate attribute.
        AttributeBuilder builder = new AttributeBuilder(attrPublicKeyCertificate);
        builder.setOption("binary");
        builder.add(ByteString.wrap(instanceKeyCertificate));
        final Attribute certificateAttr = builder.toAttribute();
        entry.addAttribute(certificateAttr, new ArrayList<ByteString>(0));

        AddOperation addOperation = icc.processAdd(entry);
        if (ResultCode.SUCCESS != addOperation.getResultCode()) {
          throw new DirectoryException(
                  addOperation.getResultCode(),
                  ERR_CRYPTOMGR_FAILED_TO_ADD_INSTANCE_KEY_ENTRY_TO_ADS.get(entry.getName()));
        }
      }
    } catch (DirectoryException ex) {
      logger.traceException(ex);
      throw new CryptoManagerException(
              ERR_CRYPTOMGR_FAILED_TO_PUBLISH_INSTANCE_KEY_ENTRY.get(
                      getExceptionMessage(ex)), ex);
    }
  }


  /**
   Return the set of valid (i.e., not tagged as compromised) instance
   key-pair public-key certificate entries in ADS.
   @return The set of valid (i.e., not tagged as compromised) instance
   key-pair public-key certificate entries in ADS represented as a Map
   from ds-cfg-key-id value to ds-cfg-public-key-certificate value.
   Note that the collection might be empty.
   @throws CryptoManagerException  In case of a problem with the
   search operation.
   @see org.opends.admin.ads.ADSContext#getTrustedCertificates()
   */
  private Map<String, byte[]> getTrustedCertificates() throws CryptoManagerException {
    final Map<String, byte[]> certificateMap = new HashMap<>();
    try {
      // Construct the search filter.
      final String FILTER_OC_INSTANCE_KEY = "(objectclass=" + ocInstanceKey.getNameOrOID() + ")";
      final String FILTER_NOT_COMPROMISED = "(!(" + attrCompromisedTime.getNameOrOID() + "=*))";
      final String searchFilter = "(&" + FILTER_OC_INSTANCE_KEY + FILTER_NOT_COMPROMISED + ")";
      final SearchRequest request = newSearchRequest(instanceKeysDN, SearchScope.SINGLE_LEVEL, searchFilter)
          .addAttribute(attrKeyID.getNameOrOID(), attrPublicKeyCertificate.getNameOrOID() + ";binary");
      InternalSearchOperation searchOp = getRootConnection().processSearch(request);
      for (Entry e : searchOp.getSearchEntries()) {
        /* attribute ds-cfg-key-id is the RDN and attribute
           ds-cfg-public-key-certificate is a MUST in the schema */
        final String keyID = e.parseAttribute(ATTR_CRYPTO_KEY_ID).asString();
        final byte[] certificate = e.parseAttribute(
            ATTR_CRYPTO_PUBLIC_KEY_CERTIFICATE).asByteString().toByteArray();
        certificateMap.put(keyID, certificate);
      }
    }
    catch (DirectoryException ex) {
      logger.traceException(ex);
      throw new CryptoManagerException(
            ERR_CRYPTOMGR_FAILED_TO_RETRIEVE_ADS_TRUSTSTORE_CERTS.get(
                    instanceKeysDN, getExceptionMessage(ex)), ex);
    }
    return certificateMap;
  }


  /**
   * Encodes a ds-cfg-symmetric-key attribute value with the preferred
   * key wrapping transformation and using the supplied arguments.
   *
   * The syntax of the ds-cfg-symmetric-key attribute:
   * <pre>
   * wrappingKeyID:wrappingTransformation:wrappedKeyAlgorithm:\
   * wrappedKeyType:hexWrappedKey
   *
   * wrappingKeyID ::= hexBytes[16]
   * wrappingTransformation
   *                   ::= e.g., RSA/ECB/OAEPWITHSHA-1ANDMGF1PADDING
   * wrappedKeyAlgorithm ::= e.g., DESede
   * hexifiedwrappedKey ::= 0123456789abcdef01...
   * </pre>
   *
   * @param wrappingKeyID The key identifier of the wrapping key. This
   * parameter is the first field in the encoded value and identifies
   * the instance that will be able to unwrap the secret key.
   *
   * @param wrappingKeyCertificateData The public key certificate used
   * to derive the wrapping key.
   *
   * @param secretKey The secret key value to be wrapped for the
   * encoded value.
   *
   * @return The encoded representation of the ds-cfg-symmetric-key
   * attribute with the secret key wrapped with the supplied public
   * key.
   *
   * @throws CryptoManagerException  If there is a problem wrapping
   * the secret key.
   */
  private String encodeSymmetricKeyAttribute(
          final String wrappingKeyID,
          final byte[] wrappingKeyCertificateData,
          final SecretKey secretKey)
          throws CryptoManagerException {
    return encodeSymmetricKeyAttribute(
            preferredKeyWrappingTransformation,
         wrappingKeyID,
         wrappingKeyCertificateData,
         secretKey);
  }


  /**
   * Encodes a ds-cfg-symmetric-key attribute value with a specified
   * key wrapping transformation and using the supplied arguments.
   *
   * @param wrappingTransformationName The name of the key wrapping
   * transformation.
   *
   * @param wrappingKeyID The key identifier of the wrapping key. This
   * parameter is the first field in the encoded value and identifies
   * the instance that will be able to unwrap the secret key.
   *
   * @param wrappingKeyCertificateData The public key certificate used
   * to derive the wrapping key.
   *
   * @param secretKey The secret key value to be wrapped for the
   * encoded value.
   *
   * @return The encoded representation of the ds-cfg-symmetric-key
   * attribute with the secret key wrapped with the supplied public
   * key.
   *
   * @throws CryptoManagerException  If there is a problem wrapping
   * the secret key.
   */
  private String encodeSymmetricKeyAttribute(
          final String wrappingTransformationName,
          final String wrappingKeyID,
          final byte[] wrappingKeyCertificateData,
          final SecretKey secretKey)
          throws CryptoManagerException {
    // Wrap secret key.
    String wrappedKeyElement;
    try {
      final CertificateFactory cf
              = CertificateFactory.getInstance("X.509");
      final Certificate certificate = cf.generateCertificate(
              new ByteArrayInputStream(wrappingKeyCertificateData));
      final Cipher wrapper
              = Cipher.getInstance(wrappingTransformationName);
      wrapper.init(Cipher.WRAP_MODE, certificate);
      byte[] wrappedKey = wrapper.wrap(secretKey);
      wrappedKeyElement = StaticUtils.bytesToHexNoSpace(wrappedKey);
    }
    catch (GeneralSecurityException ex) {
      logger.traceException(ex);
      throw new CryptoManagerException(
           ERR_CRYPTOMGR_FAILED_TO_ENCODE_SYMMETRIC_KEY_ATTRIBUTE.get(
                   getExceptionMessage(ex)), ex);
    }

    // Compose ds-cfg-symmetric-key value.
    return wrappingKeyID + ":" + wrappingTransformationName + ":"
        + secretKey.getAlgorithm() + ":" + wrappedKeyElement;
  }


  /**
   * Takes an encoded ds-cfg-symmetric-key attribute value and the
   * associated key algorithm name, and returns an initialized
   * {@code java.security.Key} object.
   * @param symmetricKeyAttribute The encoded
   * ds-cfg-symmetric-key-attribute value.
   * @return A SecretKey object instantiated with the key data,
   * algorithm, and Ciper.SECRET_KEY type, or {@code null} if the
   * supplied symmetricKeyAttribute was encoded for another instance.
   * @throws CryptoManagerException If there is a problem decomposing
   * the supplied attribute value or unwrapping the encoded key.
   */
  private SecretKey decodeSymmetricKeyAttribute(
          final String symmetricKeyAttribute)
          throws CryptoManagerException {
    // Initial decomposition.
    String[] elements = symmetricKeyAttribute.split(":", 0);
    if (4 != elements.length) {
      throw new CryptoManagerException(
         ERR_CRYPTOMGR_DECODE_SYMMETRIC_KEY_ATTRIBUTE_FIELD_COUNT.get(
                  symmetricKeyAttribute));
     }

    // Parse individual fields.
    String wrappingKeyIDElement;
    String wrappingTransformationElement;
    String wrappedKeyAlgorithmElement;
    byte[] wrappedKeyCipherTextElement;
    String fieldName = null;
    try {
      fieldName = "instance key identifier";
      wrappingKeyIDElement = elements[0];
      fieldName = "key wrapping transformation";
      wrappingTransformationElement = elements[1];
      fieldName = "wrapped key algorithm";
      wrappedKeyAlgorithmElement = elements[2];
      fieldName = "wrapped key data";
      wrappedKeyCipherTextElement
              = StaticUtils.hexStringToByteArray(elements[3]);
    }
    catch (ParseException ex) {
      logger.traceException(ex);
      throw new CryptoManagerException(
              ERR_CRYPTOMGR_DECODE_SYMMETRIC_KEY_ATTRIBUTE_SYNTAX.get(
                      symmetricKeyAttribute, fieldName,
                      ex.getErrorOffset()), ex);
    }

    // Confirm key can be unwrapped at this instance.
    final String instanceKeyID = getInstanceKeyID();
    if (! wrappingKeyIDElement.equals(instanceKeyID)) {
      return null;
    }

    // Retrieve instance-key-pair private key part.
    PrivateKey privateKey;
    try {
      privateKey = (PrivateKey) getTrustStoreBackend().getKey(ADS_CERTIFICATE_ALIAS);
    }
    catch(ConfigException ce)
    {
      throw new CryptoManagerException(
          ERR_CRYPTOMGR_DECODE_SYMMETRIC_KEY_ATTRIBUTE_NO_PRIVATE.get(getExceptionMessage(ce)), ce);
    }
    catch (IdentifiedException ex) {
      // ConfigException, DirectoryException
      logger.traceException(ex);
      throw new CryptoManagerException(
          ERR_CRYPTOMGR_DECODE_SYMMETRIC_KEY_ATTRIBUTE_NO_PRIVATE.get(getExceptionMessage(ex)), ex);
    }

    // Unwrap secret key.
    SecretKey secretKey;
    try {
      final Cipher unwrapper
              = Cipher.getInstance(wrappingTransformationElement);
      unwrapper.init(Cipher.UNWRAP_MODE, privateKey);
      secretKey = (SecretKey)unwrapper.unwrap(wrappedKeyCipherTextElement,
              wrappedKeyAlgorithmElement, Cipher.SECRET_KEY);
    } catch(GeneralSecurityException ex) {
      logger.traceException(ex);
      throw new CryptoManagerException(
            ERR_CRYPTOMGR_DECODE_SYMMETRIC_KEY_ATTRIBUTE_DECIPHER.get(
                    getExceptionMessage(ex)), ex);
    }

    return secretKey;
  }


  /**
   * Decodes the supplied symmetric key attribute value and re-encodes
   * it with the public key referred to by the requested instance key
   * identifier. The symmetric key attribute must be wrapped in this
   * instance's instance-key-pair public key.
   * @param symmetricKeyAttribute The symmetric key attribute value to
   * unwrap and rewrap.
   * @param requestedInstanceKeyID The key identifier of the public
   * key to use in the re-wrapping.
   * @return The symmetric key attribute value with the symmetric key
   * re-wrapped in the requested public key.
   * @throws CryptoManagerException If there is a problem decoding
   * the supplied symmetric key attribute value, unwrapping the
   * embedded secret key, or retrieving the requested public key.
   */
  String reencodeSymmetricKeyAttribute(
          final String symmetricKeyAttribute,
          final String requestedInstanceKeyID)
          throws CryptoManagerException {
    final SecretKey secretKey
            = decodeSymmetricKeyAttribute(symmetricKeyAttribute);
    final Map<String, byte[]> certMap = getTrustedCertificates();
    if (certMap.get(requestedInstanceKeyID) == null) {
      throw new CryptoManagerException(
          ERR_CRYPTOMGR_REWRAP_SYMMETRIC_KEY_ATTRIBUTE_NO_WRAPPER.get(
                  requestedInstanceKeyID));
    }
    final byte[] wrappingKeyCert =
            certMap.get(requestedInstanceKeyID);
    return encodeSymmetricKeyAttribute(
            preferredKeyWrappingTransformation,
         requestedInstanceKeyID, wrappingKeyCert, secretKey);
  }


  /**
   * Given a set of other servers' symmetric key values for
   * a given secret key, use the Get Symmetric Key extended
   * operation to request this server's symmetric key value.
   *
   * @param  symmetricKeys  The known symmetric key values for
   *                        a given secret key.
   *
   * @return The symmetric key value for this server, or null if
   *         none could be obtained.
   */
  private String getSymmetricKey(Set<String> symmetricKeys)
  {
    InternalClientConnection conn = getRootConnection();
    for (String symmetricKey : symmetricKeys)
    {
      try
      {
        // Get the server instance key ID from the symmetric key.
        String[] elements = symmetricKey.split(":", 0);
        String instanceKeyID = elements[0];

        // Find the server entry from the instance key ID.
        String filter = "(" + ATTR_CRYPTO_KEY_ID + "=" + instanceKeyID + ")";
        final SearchRequest request = newSearchRequest(serversDN, SearchScope.SUBORDINATES, filter);
        InternalSearchOperation internalSearch = conn.processSearch(request);
        if (internalSearch.getResultCode() != ResultCode.SUCCESS)
        {
          continue;
        }

        LinkedList<SearchResultEntry> resultEntries =
             internalSearch.getSearchEntries();
        for (SearchResultEntry resultEntry : resultEntries)
        {
          String hostname = resultEntry.parseAttribute("hostname").asString();
          Integer ldapPort = resultEntry.parseAttribute("ldapport").asInteger();

          // Connect to the server.
          AtomicInteger nextMessageID = new AtomicInteger(1);
          LDAPConnectionOptions connectionOptions =
               new LDAPConnectionOptions();
          PrintStream nullPrintStream =
               new PrintStream(new OutputStream() {
                 @Override
                 public void write ( int b ) { }
               });
          LDAPConnection connection =
               new LDAPConnection(hostname, ldapPort,
                                  connectionOptions,
                                  nullPrintStream,
                                  nullPrintStream);

          connection.connectToHost(null, null, nextMessageID);

          try
          {
            LDAPReader reader = connection.getLDAPReader();
            LDAPWriter writer = connection.getLDAPWriter();

            // Send the Get Symmetric Key extended request.

            ByteString requestValue =
                 GetSymmetricKeyExtendedOperation.encodeRequestValue(
                      symmetricKey, getInstanceKeyID());

            ExtendedRequestProtocolOp extendedRequest =
                 new ExtendedRequestProtocolOp(
                      ServerConstants.
                           OID_GET_SYMMETRIC_KEY_EXTENDED_OP,
                      requestValue);

            ArrayList<Control> controls = new ArrayList<>();
            LDAPMessage requestMessage = new LDAPMessage(
                nextMessageID.getAndIncrement(), extendedRequest, controls);
            writer.writeMessage(requestMessage);
            LDAPMessage responseMessage = reader.readMessage();

            ExtendedResponseProtocolOp extendedResponse =
                 responseMessage.getExtendedResponseProtocolOp();
            if (extendedResponse.getResultCode() ==
                 LDAPResultCode.SUCCESS)
            {
              // Got our symmetric key value.
              return extendedResponse.getValue().toString();
            }
          }
          finally
          {
            connection.close(nextMessageID);
          }
        }
      }
      catch (Exception e)
      {
        // Just try another server.
      }
    }

    // Give up.
    return null;
  }


  /**
   * Imports a cipher key entry from an entry in ADS.
   *
   * @param entry  The ADS cipher key entry to be imported.
   *               The entry will be ignored if it does not have
   *               the ds-cfg-cipher-key objectclass, or if the
   *               key is already present.
   *
   * @throws CryptoManagerException
   *               If the entry had the correct objectclass,
   *               was not already present but could not
   *               be imported.
   */
  void importCipherKeyEntry(Entry entry)
       throws CryptoManagerException
  {
    // Ignore the entry if it does not have the appropriate objectclass.
    if (!entry.hasObjectClass(ocCipherKey))
    {
      return;
    }

    try
    {
      String keyID = entry.parseAttribute(ATTR_CRYPTO_KEY_ID).asString();
      int ivLengthBits = entry.parseAttribute(
          ATTR_CRYPTO_INIT_VECTOR_LENGTH_BITS).asInteger();
      int keyLengthBits = entry.parseAttribute(
          ATTR_CRYPTO_KEY_LENGTH_BITS).asInteger();
      String transformation = entry.parseAttribute(
          ATTR_CRYPTO_CIPHER_TRANSFORMATION_NAME).asString();
      String compromisedTime = entry.parseAttribute(
          ATTR_CRYPTO_KEY_COMPROMISED_TIME).asString();

      boolean isCompromised = compromisedTime != null;

      Set<String> symmetricKeys =
          entry.parseAttribute(ATTR_CRYPTO_SYMMETRIC_KEY).asSetOfString();

      // Find the symmetric key value that was wrapped using our instance key.
      SecretKey secretKey = decodeSymmetricKeyAttribute(symmetricKeys);
      if (null != secretKey) {
        CipherKeyEntry.importCipherKeyEntry(this, keyID, transformation,
                secretKey, keyLengthBits, ivLengthBits, isCompromised);
        return;
      }

      // Request the value from another server.
      String symmetricKey = getSymmetricKey(symmetricKeys);
      if (symmetricKey == null)
      {
        throw new CryptoManagerException(
                ERR_CRYPTOMGR_IMPORT_KEY_ENTRY_FAILED_TO_DECODE.get(entry.getName()));
      }
      secretKey = decodeSymmetricKeyAttribute(symmetricKey);
      CipherKeyEntry.importCipherKeyEntry(this, keyID, transformation,
              secretKey, keyLengthBits, ivLengthBits, isCompromised);

      writeValueToEntry(entry, symmetricKey);
    }
    catch (CryptoManagerException e)
    {
      throw e;
    }
    catch (Exception ex)
    {
      logger.traceException(ex);
      throw new CryptoManagerException(
              ERR_CRYPTOMGR_IMPORT_KEY_ENTRY_FAILED_OTHER.get(
                      entry.getName(), ex.getMessage()), ex);
    }
  }

  private SecretKey decodeSymmetricKeyAttribute(Set<String> symmetricKeys) throws CryptoManagerException
  {
    for (String symmetricKey : symmetricKeys)
    {
      SecretKey secretKey = decodeSymmetricKeyAttribute(symmetricKey);
      if (secretKey != null)
      {
        return secretKey;
      }
    }
    return null;
  }


  /**
   * Imports a mac key entry from an entry in ADS.
   *
   * @param entry  The ADS mac key entry to be imported. The
   *               entry will be ignored if it does not have the
   *               ds-cfg-mac-key objectclass, or if the key is
   *               already present.
   *
   * @throws CryptoManagerException
   *               If the entry had the correct objectclass,
   *               was not already present but could not
   *               be imported.
   */
  void importMacKeyEntry(Entry entry)
       throws CryptoManagerException
  {
    // Ignore the entry if it does not have the appropriate objectclass.
    if (!entry.hasObjectClass(ocMacKey))
    {
      return;
    }

    try
    {
      String keyID = entry.parseAttribute(ATTR_CRYPTO_KEY_ID).asString();
      int keyLengthBits = entry.parseAttribute(
          ATTR_CRYPTO_KEY_LENGTH_BITS).asInteger();
      String algorithm = entry.parseAttribute(
          ATTR_CRYPTO_MAC_ALGORITHM_NAME).asString();
      String compromisedTime = entry.parseAttribute(
          ATTR_CRYPTO_KEY_COMPROMISED_TIME).asString();

      boolean isCompromised = compromisedTime != null;

      Set<String> symmetricKeys =
          entry.parseAttribute(ATTR_CRYPTO_SYMMETRIC_KEY).asSetOfString();

      SecretKey secretKey = decodeSymmetricKeyAttribute(symmetricKeys);
      if (secretKey != null)
      {
        MacKeyEntry.importMacKeyEntry(this, keyID, algorithm, secretKey, keyLengthBits, isCompromised);
        return;
      }

      // Request the value from another server.
      String symmetricKey = getSymmetricKey(symmetricKeys);
      if (symmetricKey == null)
      {
        throw new CryptoManagerException(
             ERR_CRYPTOMGR_IMPORT_KEY_ENTRY_FAILED_TO_DECODE.get(entry.getName()));
      }
      secretKey = decodeSymmetricKeyAttribute(symmetricKey);
      MacKeyEntry.importMacKeyEntry(this, keyID, algorithm, secretKey, keyLengthBits, isCompromised);

      writeValueToEntry(entry, symmetricKey);
    }
    catch (CryptoManagerException e)
    {
      throw e;
    }
    catch (Exception ex)
    {
      logger.traceException(ex);
      throw new CryptoManagerException(
              ERR_CRYPTOMGR_IMPORT_KEY_ENTRY_FAILED_OTHER.get(
                      entry.getName(), ex.getMessage()), ex);
    }
  }

  private void writeValueToEntry(Entry entry, String symmetricKey) throws CryptoManagerException
  {
    Attribute attribute = Attributes.create(ATTR_CRYPTO_SYMMETRIC_KEY, symmetricKey);
    List<Modification> modifications = newArrayList(new Modification(ModificationType.ADD, attribute));
    ModifyOperation internalModify = getRootConnection().processModify(entry.getName(), modifications);
    if (internalModify.getResultCode() != ResultCode.SUCCESS)
    {
      throw new CryptoManagerException(ERR_CRYPTOMGR_IMPORT_KEY_ENTRY_FAILED_TO_ADD_KEY.get(entry.getName()));
    }
  }

  /**
   * This class implements a utility interface to the unique
   * identifier corresponding to a cryptographic key. For each key
   * stored in an entry in ADS, the key identifier is the naming
   * attribute of the entry. The external binary representation of the
   * key entry identifier is compact, because it is typically stored
   * as a prefix of encrypted data.
   */
  private static class KeyEntryID
  {
    /** Constructs a KeyEntryID using a new unique identifier. */
    public KeyEntryID() {
      fValue = UUID.randomUUID();
    }

    /**
     * Construct a {@code KeyEntryID} from its {@code byte[]}
     * representation.
     *
     * @param keyEntryID The {@code byte[]} representation of a
     * {@code KeyEntryID}.
     */
    public KeyEntryID(final byte[] keyEntryID) {
      Reject.ifFalse(getByteValueLength() == keyEntryID.length);
      long hiBytes = 0;
      long loBytes = 0;
      for (int i = 0; i < 8; ++i) {
        hiBytes = (hiBytes << 8) | (keyEntryID[i] & 0xff);
        loBytes = (loBytes << 8) | (keyEntryID[8 + i] & 0xff);
      }
      fValue = new UUID(hiBytes, loBytes);
    }

    /**
     * Constructs a {@code KeyEntryID} from its {@code String} representation.
     *
     * @param  keyEntryID The {@code String} representation of a {@code KeyEntryID}.
     *
     * @throws  CryptoManagerException  If the argument does
     * not conform to the {@code KeyEntryID} string syntax.
     */
    public KeyEntryID(final String keyEntryID)
            throws CryptoManagerException {
      try {
        fValue = UUID.fromString(keyEntryID);
      }
      catch (IllegalArgumentException ex) {
        logger.traceException(ex);
        throw new CryptoManagerException(
                ERR_CRYPTOMGR_INVALID_KEY_IDENTIFIER_SYNTAX.get(
                        keyEntryID, getExceptionMessage(ex)), ex);
      }
    }

    /**
     * Copy constructor.
     *
     * @param keyEntryID  The {@code KeyEntryID} to copy.
     */
    public KeyEntryID(final KeyEntryID keyEntryID) {
      fValue = new UUID(keyEntryID.fValue.getMostSignificantBits(),
                        keyEntryID.fValue.getLeastSignificantBits());
    }

    /**
     * Returns the compact {@code byte[]} representation of this
     * {@code KeyEntryID}.
     * @return The compact {@code byte[]} representation of this
     * {@code KeyEntryID}.
     */
    public byte[] getByteValue(){
      final byte[] uuidBytes = new byte[16];
      long hiBytes = fValue.getMostSignificantBits();
      long loBytes = fValue.getLeastSignificantBits();
      for (int i = 7; i >= 0; --i) {
        uuidBytes[i] = (byte)hiBytes;
        hiBytes >>>= 8;
        uuidBytes[8 + i] = (byte)loBytes;
        loBytes >>>= 8;
      }
      return uuidBytes;
    }


    @Override
    public String toString() {
      return fValue.toString();
    }

    /**
     * Returns the length of the compact {@code byte[]} representation
     * of a {@code KeyEntryID}.
     *
     * @return The length of the compact {@code byte[]} representation
     * of a {@code KeyEntryID}.
     */
    public static int getByteValueLength() {
      return 16;
    }

    /**
     * Compares this object to the specified object. The result is
     * true if and only if the argument is not null, is of type
     * {@code KeyEntryID}, and has the same value (i.e., the
     * {@code String} and {@code byte[]} representations are
     * identical).
     *
     * @param obj The object to which to compare this instance.
     *
     * @return {@code true} if the objects are the same, {@code false}
     * otherwise.
     */
    @Override
    public boolean equals(final Object obj){
      return obj instanceof KeyEntryID
              && fValue.equals(((KeyEntryID) obj).fValue);
    }

    /**
     * Returns a hash code for this {@code KeyEntryID}.
     *
     * @return a hash code value for this {@code KeyEntryID}.
     */
    @Override
    public int hashCode() {
      return fValue.hashCode();
    }

    /** State. */
    private final UUID fValue;
  }


  /**
   This class corresponds to the secret key portion if a secret
   key entry in ADS.
   <p>
   Note that the generated key length is in some cases longer than requested
   key length. For example, when a 56-bit key is requested for DES (or 168-bit
   for DESede) the default provider for the Sun JRE produces an 8-byte (24-byte)
   key, which embeds the generated key in an array with one parity bit per byte.
   The requested key length is what is recorded in this object and in the
   published key entry; hence, users of the actual key data must be sure to
   operate on the full key byte array, and not truncate it to the key length.
   */
  private static class SecretKeyEntry
  {
    /**
     Construct an instance of {@code SecretKeyEntry} using the specified
     parameters. This constructor is used for key generation.
     <p>
     Note the relationship between the secret key data array length and the
     secret key length parameter described in {@link SecretKeyEntry}

     @param algorithm  The name of the secret key algorithm for which the key
     entry is to be produced.

     @param keyLengthBits  The length of the requested key in bits.

     @throws CryptoManagerException If there is a problem instantiating the key
     generator.
     */
    public SecretKeyEntry(final String algorithm, final int keyLengthBits)
    throws CryptoManagerException {
      KeyGenerator keyGen;
      int maxAllowedKeyLengthBits;
      try {
        keyGen = KeyGenerator.getInstance(algorithm);
        maxAllowedKeyLengthBits = Cipher.getMaxAllowedKeyLength(algorithm);
      }
      catch (NoSuchAlgorithmException ex) {
        throw new CryptoManagerException(
               ERR_CRYPTOMGR_INVALID_SYMMETRIC_KEY_ALGORITHM.get(
                       algorithm, getExceptionMessage(ex)), ex);
      }
      //See if key length is beyond the permissible value.
      if(maxAllowedKeyLengthBits < keyLengthBits)
      {
        throw new CryptoManagerException(
                ERR_CRYPTOMGR_INVALID_SYMMETRIC_KEY_LENGTH.get(keyLengthBits,
                maxAllowedKeyLengthBits));
      }

      keyGen.init(keyLengthBits, secureRandom);
      final byte[] key = keyGen.generateKey().getEncoded();

      this.fKeyID = new KeyEntryID();
      this.fSecretKey = new SecretKeySpec(key, algorithm);
      this.fKeyLengthBits = keyLengthBits;
      this.fIsCompromised = false;
    }


    /**
     Construct an instance of {@code SecretKeyEntry} using the specified
     parameters. This constructor would typically be used for key entries
     imported from ADS, for which the full set of parameters is known.
     <p>
     Note the relationship between the secret key data array length and the
     secret key length parameter described in {@link SecretKeyEntry}

     @param keyID  The unique identifier of this algorithm/key pair.

     @param secretKey  The secret key.

     @param secretKeyLengthBits The length in bits of the secret key.

     @param isCompromised {@code false} if the key may be used
     for operations on new data, or {@code true} if the key is being
     retained only for use in validation.
     */
    public SecretKeyEntry(final KeyEntryID keyID,
                          final SecretKey secretKey,
                          final int secretKeyLengthBits,
                          final boolean isCompromised) {
      // copy arguments
      this.fKeyID = new KeyEntryID(keyID);
      this.fSecretKey = secretKey;
      this.fKeyLengthBits = secretKeyLengthBits;
      this.fIsCompromised = isCompromised;
    }


    /**
     * The unique identifier of this algorithm/key pair.
     *
     * @return The unique identifier of this algorithm/key pair.
     */
    public KeyEntryID getKeyID() {
      return fKeyID;
    }


    /**
     * The secret key spec containing the secret key.
     *
     * @return The secret key spec containing the secret key.
     */
    public SecretKey getSecretKey() {
      return fSecretKey;
    }


    /**
     * Mark a key entry as compromised. The entry will no longer be
     * eligible for use as an encryption key.
     * <p>
     * There is no need to lock the entry to make this change: The
     * only valid transition for this field is from false to true,
     * the change is asynchronous across the topology (i.e., a key
     * might continue to be used at this instance for at least the
     * replication propagation delay after being marked compromised at
     * another instance), and modifying a boolean is guaranteed to be
     * atomic.
     */
    public void setIsCompromised() {
      fIsCompromised = true;
    }

    /**
     Returns the length of the secret key in bits.
     <p>
     Note the relationship between the secret key data array length and the
     secret key length parameter described in {@link SecretKeyEntry}

     @return the length of the secret key in bits.
     */
    public int getKeyLengthBits() {
      return fKeyLengthBits;
    }

    /**
     * Returns the status of the key.
     * @return  {@code false} if the key may be used for operations on
     * new data, or {@code true} if the key is being retained only for
     * use in validation.
     */
    public boolean isCompromised() {
      return fIsCompromised;
    }

    /** State. */
    private final KeyEntryID fKeyID;
    private final SecretKey fSecretKey;
    private final int fKeyLengthBits;
    private boolean fIsCompromised;
  }

  private static void putSingleValueAttribute(
      Map<AttributeType, List<Attribute>> attrs, AttributeType type, String value)
  {
    attrs.put(type, Attributes.createAsList(type, value));
  }

  /**
   * This class corresponds to the cipher key entry in ADS. It is
   * used in the local cache of key entries that have been requested
   * by CryptoManager clients.
   */
  private static class CipherKeyEntry extends SecretKeyEntry
  {
    /**
     * This method generates a key according to the key parameters,
     * and creates a key entry and registers it in the supplied map.
     *
     * @param  cryptoManager The CryptoManager instance for which the
     * key is to be generated. Pass {@code null} as the argument to
     * this parameter in order to validate a proposed cipher
     * transformation and key length without publishing the key.
     *
     * @param transformation  The cipher transformation for which the
     * key is to be produced. This argument is required.
     *
     * @param keyLengthBits  The cipher key length in bits. This argument is
     * required and must be suitable for the requested transformation.
     *
     * @return The key entry corresponding to the parameters.
     *
     * @throws CryptoManagerException If there is a problem
     * instantiating a Cipher object in order to validate the supplied
     * parameters when creating a new entry.
     *
     * @see MacKeyEntry#getMacKeyEntryOrNull(CryptoManagerImpl, String, int)
     */
    public static CipherKeyEntry generateKeyEntry(
            final CryptoManagerImpl cryptoManager,
            final String transformation,
            final int keyLengthBits)
    throws CryptoManagerException {
      final Map<KeyEntryID, CipherKeyEntry> cache =
          cryptoManager != null ? cryptoManager.cipherKeyEntryCache : null;

      CipherKeyEntry keyEntry = new CipherKeyEntry(transformation,
              keyLengthBits);

      // Validate the key entry. Record the initialization vector length, if any
      final Cipher cipher = getCipher(keyEntry, Cipher.ENCRYPT_MODE, null);
      // TODO: https://opends.dev.java.net/issues/show_bug.cgi?id=2471
      final byte[] iv = cipher.getIV();
      keyEntry.setIVLengthBits(null == iv ? 0 : iv.length * Byte.SIZE);

      if (null != cache) {
        /* The key is published to ADS before making it available in the local
           cache with the intention to ensure the key is persisted before use.
           This ordering allows the possibility that data encrypted at another
           instance could arrive at this instance before the key is available in
           the local cache to decode the data. */
        publishKeyEntry(cryptoManager, keyEntry);
        cache.put(keyEntry.getKeyID(), keyEntry);
      }

      return keyEntry;
    }


    /**
     * Publish a new cipher key by adding an entry into ADS.
     * @param  cryptoManager The CryptoManager instance for which the
     *                       key was generated.
     * @param  keyEntry      The cipher key to be published.
     * @throws CryptoManagerException
     *                       If the key entry could not be added to
     *                       ADS.
     */
    private static void publishKeyEntry(CryptoManagerImpl cryptoManager,
                                        CipherKeyEntry keyEntry)
         throws CryptoManagerException
    {
      // Construct the key entry DN.
      ByteString distinguishedValue =
           ByteString.valueOfUtf8(keyEntry.getKeyID().toString());
      DN entryDN = secretKeysDN.child(
           new RDN(attrKeyID, distinguishedValue));

      // Set the entry object classes.
      LinkedHashMap<ObjectClass,String> ocMap = new LinkedHashMap<>(2);
      ocMap.put(DirectoryServer.getTopObjectClass(), OC_TOP);
      ocMap.put(ocCipherKey, OC_CRYPTO_CIPHER_KEY);

      // Create the user attributes.
      LinkedHashMap<AttributeType, List<Attribute>> userAttrs = new LinkedHashMap<>();
      userAttrs.put(attrKeyID, Attributes.createAsList(attrKeyID, distinguishedValue));
      putSingleValueAttribute(userAttrs, attrTransformation, keyEntry.getType());
      putSingleValueAttribute(userAttrs, attrInitVectorLength,
          String.valueOf(keyEntry.getIVLengthBits()));
      putSingleValueAttribute(userAttrs, attrKeyLength,
          String.valueOf(keyEntry.getKeyLengthBits()));
      userAttrs.put(attrSymmetricKey, buildSymetricKeyAttributes(cryptoManager, keyEntry.getSecretKey()));

      // Create the entry.
      LinkedHashMap<AttributeType, List<Attribute>> opAttrs = new LinkedHashMap<>(0);
      Entry entry = new Entry(entryDN, ocMap, userAttrs, opAttrs);
      AddOperation addOperation = getRootConnection().processAdd(entry);
      if (addOperation.getResultCode() != ResultCode.SUCCESS)
      {
        throw new CryptoManagerException(
                ERR_CRYPTOMGR_SYMMETRIC_KEY_ENTRY_ADD_FAILED.get(
                        entry.getName(), addOperation.getErrorMessage()));
      }
    }

    /**
     * Initializes a secret key entry from the supplied parameters,
     * validates it, and registers it in the supplied map.
     * The anticipated use of this method is to import a key entry from ADS.
     *
     * @param cryptoManager  The CryptoManager instance.
     * @param keyIDString  The key identifier.
     * @param transformation The cipher transformation for which the key entry was produced.
     * @param secretKey  The cipher key.
     * @param secretKeyLengthBits The length of the cipher key in bits.
     * @param ivLengthBits  The length of the initialization vector,
     * which will be zero in the case of any stream cipher algorithm,
     * any block cipher algorithm for which the transformation mode
     * does not use an initialization vector, and any HMAC algorithm.
     * @param isCompromised  Mark the key as compromised, so that it
     * will not subsequently be used for encryption. The key entry
     * must be maintained in order to decrypt existing ciphertext.
     * @return  The key entry, if one was successfully produced.
     * @throws CryptoManagerException  In case of an error in the
     * parameters used to initialize or validate the key entry.
     */
    public static CipherKeyEntry importCipherKeyEntry(
            final CryptoManagerImpl cryptoManager,
            final String keyIDString,
            final String transformation,
            final SecretKey secretKey,
            final int secretKeyLengthBits,
            final int ivLengthBits,
            final boolean isCompromised)
            throws CryptoManagerException {
      Reject.ifNull(keyIDString, transformation, secretKey);
      Reject.ifFalse(0 <= ivLengthBits);

      final KeyEntryID keyID = new KeyEntryID(keyIDString);

      // Check map for existing key entry with the supplied keyID.
      CipherKeyEntry keyEntry = getCipherKeyEntryOrNull(cryptoManager, keyID);
      if (null != keyEntry) {
        // Paranoiac check to ensure exact type match.
        if (!keyEntry.getType().equals(transformation)
            || keyEntry.getKeyLengthBits() != secretKeyLengthBits
            || keyEntry.getIVLengthBits() != ivLengthBits) {
          throw new CryptoManagerException(
                    ERR_CRYPTOMGR_IMPORT_KEY_ENTRY_FIELD_MISMATCH.get(keyIDString));
        }
        // Allow transition to compromised.
        if (isCompromised && !keyEntry.isCompromised()) {
          keyEntry.setIsCompromised();
        }
        return keyEntry;
      }

      // Instantiate new entry.
      keyEntry = new CipherKeyEntry(keyID, transformation, secretKey,
              secretKeyLengthBits, ivLengthBits, isCompromised);

      // Validate new entry.
      byte[] iv = null;
      if (0 < ivLengthBits) {
        iv = new byte[ivLengthBits / Byte.SIZE];
        secureRandom.nextBytes(iv);
      }
      getCipher(keyEntry, Cipher.DECRYPT_MODE, iv);

      // Cache new entry
      cryptoManager.cipherKeyEntryLock.lock();
      try
      {
        cryptoManager.cipherKeyEntryCache.put(keyEntry.getKeyID(), keyEntry);
        cryptoManager.mostRecentCipherKeys.put(cryptoManager.getKeyFullSpec(transformation, secretKeyLengthBits),
            keyEntry);
      }
      finally
      {
        cryptoManager.cipherKeyEntryLock.unlock();
      }

      return keyEntry;
    }


    /**
     * Retrieve a CipherKeyEntry from the CipherKeyEntry Map based on
     * the algorithm name and key length.
     * <p>
     * ADS is not searched in the case a key entry meeting the
     * specifications is not found. Instead, the ADS monitoring thread
     * is responsible for asynchronous updates to the key map.
     *
     * @param cryptoManager  The CryptoManager instance with which the
     * key entry is associated.
     * @param transformation  The cipher transformation for which the
     * key was produced.
     * @param keyLengthBits  The cipher key length in bits.
     *
     * @return  The key entry corresponding to the parameters, or
     * {@code null} if no such entry exists or has been compromised
     */
    public static CipherKeyEntry getCipherKeyEntryOrNull(
        final CryptoManagerImpl cryptoManager,
        final String transformation,
        final int keyLengthBits) {
      Reject.ifNull(cryptoManager, transformation);
      Reject.ifFalse(0 < keyLengthBits);

      CipherKeyEntry key = cryptoManager.mostRecentCipherKeys.get(cryptoManager.getKeyFullSpec(transformation,
          keyLengthBits));
      return key != null && !key.isCompromised() ? key : null;
    }


    /**
     * Given a key identifier, return the associated cipher key entry
     * from the supplied map. This method would typically be used by
     * a decryption routine.
     * <p>
     * Although the existence of data tagged with the requested keyID
     * implies the key entry exists in the system, it is possible for
     * the distribution of the key entry to lag that of the data;
     * hence this routine might return null. No attempt is made to
     * query the other instances in the ADS topology (presumably at
     * least the instance producing the key entry will have it), due
     * to the presumed infrequency of key generation and expected low
     * latency of replication, compared to the complexity of finding
     * the set of instances and querying them. Instead, the caller
     * must retry the operation requesting the decryption.
     *
     * @param cryptoManager  The CryptoManager instance with which the
     * key entry is associated.
     * @param keyID  The key identifier.
     *
     * @return  The key entry associated with the key identifier, or
     * {@code null} if no such entry exists.
     *
     * @see CryptoManagerImpl.MacKeyEntry
     *  #getMacKeyEntryOrNull(CryptoManagerImpl, String, int)
     */
    public static CipherKeyEntry getCipherKeyEntryOrNull(CryptoManagerImpl cryptoManager, final KeyEntryID keyID) {
      return cryptoManager.cipherKeyEntryCache.get(keyID);
    }

    /**
     In case a transformation is supplied instead of an algorithm:
     E.g., AES/CBC/PKCS5Padding -> AES.

     @param transformation The cipher transformation from which to
     extract the cipher algorithm.

     @return  The algorithm prefix of the Cipher transformation. If
     the transformation is supplied as an algorithm-only (no mode or
     padding), return the transformation as-is.
     */
    private static String keyAlgorithmFromTransformation(
            String transformation){
      final int separatorIndex = transformation.indexOf('/');
      return 0 < separatorIndex
              ? transformation.substring(0, separatorIndex)
              : transformation;
    }

    /**
     * Construct an instance of {@code CipherKeyEntry} using the
     * specified parameters. This constructor would typically be used
     * for key generation.
     *
     * @param transformation  The name of the Cipher transformation
     * for which the key entry is to be produced.
     *
     * @param keyLengthBits  The length of the requested key in bits.
     *
     * @throws CryptoManagerException If there is a problem
     * instantiating the key generator.
     */
    private CipherKeyEntry(final String transformation, final int keyLengthBits)
            throws CryptoManagerException {
      // Generate a new key.
      super(keyAlgorithmFromTransformation(transformation), keyLengthBits);

      // copy arguments.
      this.fType = transformation;
      this.fIVLengthBits = -1; /* compute IV length */
    }

    /**
     * Construct an instance of CipherKeyEntry using the specified
     * parameters. This constructor would typically be used for key
     * entries imported from ADS, for which the full set of parameters
     * is known, and for a newly generated key entry, for which the
     * initialization vector length might not yet be known, but which
     * must be set prior to using the key.
     *
     * @param keyID  The unique identifier of this cipher
     * transformation/key pair.
     *
     * @param transformation  The name of the secret-key cipher
     * transformation for which the key entry is to be produced.
     *
     * @param secretKey  The cipher key.
     *
     * @param secretKeyLengthBits  The length of the secret key in bits.
     *
     * @param ivLengthBits  The length in bits of a mandatory
     * initialization vector or 0 if none is required. Set this
     * parameter to -1 when generating a new encryption key and this
     * method will attempt to compute the proper value by first using
     * the cipher block size and then, if the cipher block size is
     * non-zero, using 0 (i.e., no initialization vector).
     *
     * @param isCompromised {@code false} if the key may be used
     * for encryption, or {@code true} if the key is being retained
     * only for use in decrypting existing data.
     *
     * @throws  CryptoManagerException If there is a problem
     * instantiating a Cipher object in order to validate the supplied
     * parameters when creating a new entry.
     */
    private CipherKeyEntry(final KeyEntryID keyID,
                           final String transformation,
                           final SecretKey secretKey,
                           final int secretKeyLengthBits,
                           final int ivLengthBits,
                           final boolean isCompromised)
            throws CryptoManagerException {
      super(keyID, secretKey, secretKeyLengthBits, isCompromised);

      // copy arguments
      this.fType = transformation;
      this.fIVLengthBits = ivLengthBits;
    }


    /**
     * The cipher transformation for which the key entry was created.
     *
     * @return The cipher transformation.
     */
    public String getType() {
      return fType;
    }

    /**
     * Set the algorithm/key pair's required initialization vector
     * length in bits. Typically, this will be the cipher's block
     * size, or 0 for a stream cipher or a block cipher mode that does
     * not use an initialization vector (e.g., ECB).
     *
     * @param ivLengthBits The initialization vector length in bits.
     */
    private void setIVLengthBits(int ivLengthBits) {
      Reject.ifFalse(-1 == fIVLengthBits && 0 <= ivLengthBits);
      fIVLengthBits = ivLengthBits;
    }

    /**
     * The initialization vector length in bits: 0 is a stream cipher
     * or a block cipher that does not use an IV (e.g., ECB); or a
     * positive integer, typically the block size of the cipher.
     * <p>
     * This method returns -1 if the object initialization has not
     * been completed.
     *
     * @return The initialization vector length.
     */
    public int getIVLengthBits() {
      return fIVLengthBits;
    }

    /** State. */
    private final String fType;
    private int fIVLengthBits = -1;
  }


  /**
   * This method produces an initialized Cipher based on the supplied
   * CipherKeyEntry's state.
   *
   * @param keyEntry  The secret key entry containing the cipher
   * transformation and secret key for which to instantiate
   * the cipher.
   *
   * @param mode  Either Cipher.ENCRYPT_MODE or Cipher.DECRYPT_MODE.
   *
   * @param initializationVector  For Cipher.DECRYPT_MODE, supply
   * the initialization vector used in the corresponding encryption
   * cipher, or {@code null} if none.
   *
   * @return  The initialized cipher object.
   *
   * @throws  CryptoManagerException In case of a problem creating
   * or initializing the requested cipher object. Possible causes
   * include NoSuchAlgorithmException, NoSuchPaddingException,
   * InvalidKeyException, and InvalidAlgorithmParameterException.
   */
  private static Cipher getCipher(final CipherKeyEntry keyEntry,
                                  final int mode,
                                  final byte[] initializationVector)
          throws CryptoManagerException {
    Reject.ifFalse(Cipher.ENCRYPT_MODE == mode
            || Cipher.DECRYPT_MODE == mode);
    Reject.ifFalse(Cipher.ENCRYPT_MODE != mode
            || null == initializationVector);
    Reject.ifFalse(-1 != keyEntry.getIVLengthBits()
            || Cipher.ENCRYPT_MODE == mode);
    Reject.ifFalse(null == initializationVector
            || initializationVector.length * Byte.SIZE
                                       == keyEntry.getIVLengthBits());

    Cipher cipher;
    try {
      String transformation = keyEntry.getType();
      /* If a client specifies only an algorithm for a transformation, the
         Cipher provider can supply default values for mode and padding. Hence
         in order to avoid a decryption error due to mismatched defaults in the
         provider implementation of JREs supplied by different vendors, the
         {@code CryptoManager} configuration validator requires the mode and
         padding be explicitly specified. Some cipher algorithms, including
         RC4 and ARCFOUR, do not have a mode or padding, and hence must be
         specified as {@code algorithm/NONE/NoPadding}. */
      String fields[] = transformation.split("/",0);
      if (1 < fields.length && "NONE".equals(fields[1])) {
        assert "RC4".equals(fields[0]) || "ARCFOUR".equals(fields[0]);
        assert "NoPadding".equals(fields[2]);
        transformation = fields[0];
      }
      cipher = Cipher.getInstance(transformation);
    }
    catch (GeneralSecurityException ex) {
      // NoSuchAlgorithmException, NoSuchPaddingException
      logger.traceException(ex);
      throw new CryptoManagerException(
           ERR_CRYPTOMGR_GET_CIPHER_INVALID_CIPHER_TRANSFORMATION.get(
                   keyEntry.getType(), getExceptionMessage(ex)), ex);
    }

    try {
      if (0 < keyEntry.getIVLengthBits()) {
        byte[] iv;
        if (Cipher.ENCRYPT_MODE == mode && null == initializationVector) {
          iv = new byte[keyEntry.getIVLengthBits() / Byte.SIZE];
          secureRandom.nextBytes(iv);
        }
        else {
          iv = initializationVector;
        }
        // TODO: https://opends.dev.java.net/issues/show_bug.cgi?id=2471
        cipher.init(mode, keyEntry.getSecretKey(), new IvParameterSpec(iv));
      }
      else {
        cipher.init(mode, keyEntry.getSecretKey());
      }
    }
    catch (GeneralSecurityException ex) {
      // InvalidKeyException, InvalidAlgorithmParameterException
      logger.traceException(ex);
      throw new CryptoManagerException(
              ERR_CRYPTOMGR_GET_CIPHER_CANNOT_INITIALIZE.get(
                      getExceptionMessage(ex)), ex);
    }

    return cipher;
  }


  /**
   * This class corresponds to the MAC key entry in ADS. It is
   * used in the local cache of key entries that have been requested
   * by CryptoManager clients.
   */
  private static class MacKeyEntry extends SecretKeyEntry
  {
    /**
     * This method generates a key according to the key parameters,
     * creates a key entry, and optionally registers it in the
     * supplied CryptoManager context.
     *
     * @param  cryptoManager The CryptoManager instance for which the
     * key is to be generated. Pass {@code null} as the argument to
     * this parameter in order to validate a proposed MAC algorithm
     * and key length, but not publish the key entry.
     *
     * @param algorithm  The MAC algorithm for which the
     * key is to be produced. This argument is required.
     *
     * @param keyLengthBits  The MAC key length in bits. The argument is
     * required and must be suitable for the requested algorithm.
     *
     * @return The key entry corresponding to the parameters.
     *
     * @throws CryptoManagerException If there is a problem
     * instantiating a Mac object in order to validate the supplied
     * parameters when creating a new entry.
     *
     * @see CipherKeyEntry#getCipherKeyEntryOrNull(CryptoManagerImpl, String, int)
     */
    public static MacKeyEntry generateKeyEntry(
            final CryptoManagerImpl cryptoManager,
            final String algorithm,
            final int keyLengthBits)
    throws CryptoManagerException {
      Reject.ifNull(algorithm);

      final Map<KeyEntryID, MacKeyEntry> cache =
          cryptoManager != null ? cryptoManager.macKeyEntryCache : null;

      final MacKeyEntry keyEntry = new MacKeyEntry(algorithm, keyLengthBits);

      // Validate the key entry.
      getMacEngine(keyEntry);

      if (null != cache) {
        /* The key is published to ADS before making it available in the local
           cache with the intention to ensure the key is persisted before use.
           This ordering allows the possibility that data encrypted at another
           instance could arrive at this instance before the key is available in
           the local cache to decode the data. */
        publishKeyEntry(cryptoManager, keyEntry);
        cache.put(keyEntry.getKeyID(), keyEntry);
      }

      return keyEntry;
    }


    /**
     * Publish a new mac key by adding an entry into ADS.
     * @param  cryptoManager The CryptoManager instance for which the
     *                       key was generated.
     * @param  keyEntry      The mac key to be published.
     * @throws CryptoManagerException
     *                       If the key entry could not be added to
     *                       ADS.
     */
    private static void publishKeyEntry(CryptoManagerImpl cryptoManager,
                                        MacKeyEntry keyEntry)
         throws CryptoManagerException
    {
      // Construct the key entry DN.
      ByteString distinguishedValue =
           ByteString.valueOfUtf8(keyEntry.getKeyID().toString());
      DN entryDN = secretKeysDN.child(
           new RDN(attrKeyID, distinguishedValue));

      // Set the entry object classes.
      LinkedHashMap<ObjectClass,String> ocMap = new LinkedHashMap<>(2);
      ocMap.put(DirectoryServer.getTopObjectClass(), OC_TOP);
      ocMap.put(ocMacKey, OC_CRYPTO_MAC_KEY);

      // Create the user attributes.
      LinkedHashMap<AttributeType, List<Attribute>> userAttrs = new LinkedHashMap<>();
      userAttrs.put(attrKeyID, Attributes.createAsList(attrKeyID, distinguishedValue));
      putSingleValueAttribute(userAttrs, attrMacAlgorithm, keyEntry.getType());
      putSingleValueAttribute(userAttrs, attrKeyLength, String.valueOf(keyEntry.getKeyLengthBits()));
      userAttrs.put(attrSymmetricKey, buildSymetricKeyAttributes(cryptoManager, keyEntry.getSecretKey()));

      // Create the entry.
      LinkedHashMap<AttributeType, List<Attribute>> opAttrs = new LinkedHashMap<>(0);
      Entry entry = new Entry(entryDN, ocMap, userAttrs, opAttrs);
      AddOperation addOperation = getRootConnection().processAdd(entry);
      if (addOperation.getResultCode() != ResultCode.SUCCESS)
      {
        throw new CryptoManagerException(
                ERR_CRYPTOMGR_SYMMETRIC_KEY_ENTRY_ADD_FAILED.get(
                        entry.getName(), addOperation.getErrorMessage()));
      }
    }

    /**
     * Initializes a secret key entry from the supplied parameters,
     * validates it, and registers it in the supplied map.
     * The anticipated use of this method is to import a key entry from ADS.
     *
     * @param cryptoManager  The CryptoManager instance.
     * @param keyIDString  The key identifier.
     * @param algorithm  The name of the MAC algorithm for which the
     * key entry is to be produced.
     * @param secretKey  The MAC key.
     * @param secretKeyLengthBits The length of the secret key in bits.
     * @param isCompromised Mark the key as compromised, so that it
     * will not subsequently be used for new data. The key entry
     * must be maintained in order to verify existing signatures.
     * @return The key entry, if one was successfully produced.
     * @throws CryptoManagerException  In case of an error in the
     * parameters used to initialize or validate the key entry.
     */
    public static MacKeyEntry importMacKeyEntry(
            final CryptoManagerImpl cryptoManager,
            final String keyIDString,
            final String algorithm,
            final SecretKey secretKey,
            final int secretKeyLengthBits,
            final boolean isCompromised)
            throws CryptoManagerException {
      Reject.ifNull(keyIDString, secretKey);

      final KeyEntryID keyID = new KeyEntryID(keyIDString);

      // Check map for existing key entry with the supplied keyID.
      MacKeyEntry keyEntry = getMacKeyEntryOrNull(cryptoManager, keyID);
      if (null != keyEntry) {
        // Paranoiac check to ensure exact type match.
        if (! (keyEntry.getType().equals(algorithm)
                && keyEntry.getKeyLengthBits() == secretKeyLengthBits)) {
               throw new CryptoManagerException(
                    ERR_CRYPTOMGR_IMPORT_KEY_ENTRY_FIELD_MISMATCH.get(
                         keyIDString));
        }
        // Allow transition to compromised.
        if (isCompromised && !keyEntry.isCompromised()) {
          keyEntry.setIsCompromised();
        }
        return keyEntry;
      }

      // Instantiate new entry.
      keyEntry = new MacKeyEntry(keyID, algorithm, secretKey,
              secretKeyLengthBits, isCompromised);

      // Validate new entry.
      getMacEngine(keyEntry);

      // Cache new entry
      cryptoManager.macKeyEntryLock.lock();
      try
      {
        cryptoManager.macKeyEntryCache.put(keyEntry.getKeyID(), keyEntry);
        cryptoManager.mostRecentMacKeys.put(cryptoManager.getKeyFullSpec(algorithm, secretKeyLengthBits), keyEntry);
      }
      finally
      {
        cryptoManager.macKeyEntryLock.unlock();
      }
      return keyEntry;
    }


    /**
     * Retrieve a MacKeyEntry from the MacKeyEntry Map based on
     * the algorithm name and key length.
     * <p>
     * ADS is not searched in the case a key entry meeting the
     * specifications is not found. Instead, the ADS monitoring thread
     * is responsible for asynchronous updates to the key map.
     *
     * @param cryptoManager  The CryptoManager instance with which the
     * key entry is associated.
     * @param algorithm  The MAC algorithm for which the key was produced.
     * @param keyLengthBits  The MAC key length in bits.
     *
     * @return  The key entry corresponding to the parameters, or
     * {@code null} if no such entry exists or has been compromised
     */
    public static MacKeyEntry getMacKeyEntryOrNull(
        final CryptoManagerImpl cryptoManager,
        final String algorithm,
        final int keyLengthBits) {
      Reject.ifNull(cryptoManager, algorithm);
      Reject.ifFalse(0 < keyLengthBits);

      MacKeyEntry key =cryptoManager.mostRecentMacKeys.get(cryptoManager.getKeyFullSpec(algorithm, keyLengthBits));
      return key != null && !key.isCompromised() ? key : null;
    }


    /**
     * Given a key identifier, return the associated cipher key entry
     * from the supplied map. This method would typically be used by
     * a decryption routine.
     * <p>
     * Although the existence of data tagged with the requested keyID
     * implies the key entry exists in the system, it is possible for
     * the distribution of the key entry to lag that of the data;
     * hence this routine might return null. No attempt is made to
     * query the other instances in the ADS topology (presumably at
     * least the instance producing the key entry will have it), due
     * to the presumed infrequency of key generation and expected low
     * latency of replication, compared to the complexity of finding
     * the set of instances and querying them. Instead, the caller
     * must retry the operation requesting the decryption.
     *
     * @param cryptoManager  The CryptoManager instance with which the
     * key entry is associated.
     * @param keyID  The key identifier.
     *
     * @return  The key entry associated with the key identifier, or
     * {@code null} if no such entry exists.
     *
     * @see CryptoManagerImpl.CipherKeyEntry
     *     #getMacKeyEntryOrNull(CryptoManagerImpl, String, int)
     */
    public static MacKeyEntry getMacKeyEntryOrNull(final CryptoManagerImpl cryptoManager, final KeyEntryID keyID) {
      return cryptoManager.macKeyEntryCache.get(keyID);
    }

    /**
     * Construct an instance of {@code MacKeyEntry} using the
     * specified parameters. This constructor would typically be used
     * for key generation.
     *
     * @param algorithm  The name of the MAC algorithm for which the
     * key entry is to be produced.
     *
     * @param keyLengthBits  The length of the requested key in bits.
     *
     * @throws CryptoManagerException If there is a problem
     * instantiating the key generator.
     */
    private MacKeyEntry(final String algorithm,
                        final int keyLengthBits)
            throws CryptoManagerException {
      // Generate a new key.
      super(algorithm, keyLengthBits);

      // copy arguments
      this.fType = algorithm;
    }

    /**
     * Construct an instance of MacKeyEntry using the specified
     * parameters. This constructor would typically be used for key
     * entries imported from ADS, for which the full set of parameters is known.
     *
     * @param keyID  The unique identifier of this MAC algorithm/key pair.
     * @param algorithm  The name of the MAC algorithm for which the
     * key entry is to be produced.
     * @param secretKey  The MAC key.
     * @param secretKeyLengthBits  The length of the secret key in bits.
     *
     * @param isCompromised {@code false} if the key may be used
     * for signing, or {@code true} if the key is being retained only
     * for use in signature verification.
     */
    private MacKeyEntry(final KeyEntryID keyID,
                        final String algorithm,
                        final SecretKey secretKey,
                        final int secretKeyLengthBits,
                        final boolean isCompromised) {
      super(keyID, secretKey, secretKeyLengthBits, isCompromised);

      // copy arguments
      this.fType = algorithm;
    }


    /**
     * The algorithm for which the key entry was created.
     *
     * @return The algorithm.
     */
    public String getType() {
      return fType;
    }

    /** State. */
    private final String fType;
  }

  private static List<Attribute> buildSymetricKeyAttributes(CryptoManagerImpl cryptoManager, SecretKey secretKey)
      throws CryptoManagerException
  {
    Map<String, byte[]> trustedCerts = cryptoManager.getTrustedCertificates();

    // Need to add our own instance certificate.
    byte[] instanceKeyCertificate = CryptoManagerImpl.getInstanceKeyCertificateFromLocalTruststore();
    trustedCerts.put(getInstanceKeyID(instanceKeyCertificate), instanceKeyCertificate);

    AttributeBuilder builder = new AttributeBuilder(attrSymmetricKey);
    for (Map.Entry<String, byte[]> mapEntry : trustedCerts.entrySet())
    {
      String symmetricKey =
          cryptoManager.encodeSymmetricKeyAttribute(mapEntry.getKey(), mapEntry.getValue(), secretKey);
      builder.add(symmetricKey);
    }
    return builder.toAttributeList();
  }

  /**
   * This method produces an initialized MAC engine based on the
   * supplied MacKeyEntry's state.
   *
   * @param keyEntry The MacKeyEntry specifying the Mac properties.
   *
   * @return  An initialized Mac object.
   *
   * @throws CryptoManagerException  In case there was a error
   * instantiating the Mac object.
   */
  private static Mac getMacEngine(MacKeyEntry keyEntry)
          throws CryptoManagerException
  {
    Mac mac;
    try {
      mac = Mac.getInstance(keyEntry.getType());
    }
    catch (NoSuchAlgorithmException ex){
      logger.traceException(ex);
      throw new CryptoManagerException(
              ERR_CRYPTOMGR_GET_MAC_ENGINE_INVALID_MAC_ALGORITHM.get(
                      keyEntry.getType(), getExceptionMessage(ex)),
              ex);
    }

    try {
      mac.init(keyEntry.getSecretKey());
    }
    catch (InvalidKeyException ex) {
      logger.traceException(ex);
      throw new CryptoManagerException(
           ERR_CRYPTOMGR_GET_MAC_ENGINE_CANNOT_INITIALIZE.get(
                   getExceptionMessage(ex)), ex);
    }

    return mac;
  }

  @Override
  public String getPreferredMessageDigestAlgorithm()
  {
    return preferredDigestAlgorithm;
  }

  @Override
  public MessageDigest getPreferredMessageDigest()
         throws NoSuchAlgorithmException
  {
    return MessageDigest.getInstance(preferredDigestAlgorithm);
  }

  @Override
  public MessageDigest getMessageDigest(String digestAlgorithm)
         throws NoSuchAlgorithmException
  {
    return MessageDigest.getInstance(digestAlgorithm);
  }

  @Override
  public byte[] digest(byte[] data)
         throws NoSuchAlgorithmException
  {
    return MessageDigest.getInstance(preferredDigestAlgorithm).
                digest(data);
  }

  @Override
  public byte[] digest(String digestAlgorithm, byte[] data)
         throws NoSuchAlgorithmException
  {
    return MessageDigest.getInstance(digestAlgorithm).digest(data);
  }

  @Override
  public byte[] digest(InputStream inputStream)
         throws IOException, NoSuchAlgorithmException
  {
    MessageDigest digest =
         MessageDigest.getInstance(preferredDigestAlgorithm);

    byte[] buffer = new byte[8192];
    while (true)
    {
      int bytesRead = inputStream.read(buffer);
      if (bytesRead < 0)
      {
        break;
      }

      digest.update(buffer, 0, bytesRead);
    }

    return digest.digest();
  }

  @Override
  public byte[] digest(String digestAlgorithm,
                       InputStream inputStream)
         throws IOException, NoSuchAlgorithmException
  {
    MessageDigest digest = MessageDigest.getInstance(digestAlgorithm);

    byte[] buffer = new byte[8192];
    while (true)
    {
      int bytesRead = inputStream.read(buffer);
      if (bytesRead < 0)
      {
        break;
      }

      digest.update(buffer, 0, bytesRead);
    }

    return digest.digest();
  }

  @Override
  public String getMacEngineKeyEntryID()
          throws CryptoManagerException
  {
    return getMacEngineKeyEntryID(preferredMACAlgorithm,
            preferredMACAlgorithmKeyLengthBits);
  }

  @Override
  public String getMacEngineKeyEntryID(final String macAlgorithm,
                                       final int keyLengthBits)
         throws CryptoManagerException {
    Reject.ifNull(macAlgorithm);

    MacKeyEntry keyEntry = MacKeyEntry.getMacKeyEntryOrNull(this, macAlgorithm, keyLengthBits);
    if (keyEntry == null)
    {
      macKeyEntryLock.lock();
      try
      {
        keyEntry = MacKeyEntry.getMacKeyEntryOrNull(this, macAlgorithm, keyLengthBits);
        if (keyEntry == null)
        {
          keyEntry = MacKeyEntry.generateKeyEntry(this, macAlgorithm, keyLengthBits);
          mostRecentMacKeys.put(getKeyFullSpec(macAlgorithm, keyLengthBits), keyEntry);
        }
      }
      finally
      {
        macKeyEntryLock.unlock();
      }
    }

    return keyEntry.getKeyID().toString();
  }

  @Override
  public Mac getMacEngine(String keyEntryID)
          throws CryptoManagerException
  {
    final MacKeyEntry keyEntry = MacKeyEntry.getMacKeyEntryOrNull(this, new KeyEntryID(keyEntryID));
    return keyEntry != null ? getMacEngine(keyEntry) : null;
  }

  @Override
  public byte[] encrypt(byte[] data)
         throws GeneralSecurityException, CryptoManagerException
  {
    return encrypt(preferredCipherTransformation,
            preferredCipherTransformationKeyLengthBits, data);
  }

  @Override
  public byte[] encrypt(String cipherTransformation,
                        int keyLengthBits,
                        byte[] data)
         throws GeneralSecurityException, CryptoManagerException
  {
    Reject.ifNull(cipherTransformation, data);

    CipherKeyEntry keyEntry = getCipherKeyEntry(cipherTransformation, keyLengthBits);
    final Cipher cipher = getCipher(keyEntry, Cipher.ENCRYPT_MODE, null);
    final byte[] keyID = keyEntry.getKeyID().getByteValue();
    final byte[] iv = cipher.getIV();
    final int prologueLength = /* version */ 1 + keyID.length + (iv != null ? iv.length : 0);
    final int dataLength = cipher.getOutputSize(data.length);
    final byte[] cipherText = new byte[prologueLength + dataLength];
    int writeIndex = 0;

    cipherText[writeIndex++] = CIPHERTEXT_PROLOGUE_VERSION;
    System.arraycopy(keyID, 0, cipherText, writeIndex, keyID.length);
    writeIndex += keyID.length;
    if (null != iv) {
      System.arraycopy(iv, 0, cipherText, writeIndex, iv.length);
      writeIndex += iv.length;
    }
    System.arraycopy(cipher.doFinal(data), 0, cipherText,
                     prologueLength, dataLength);
    return cipherText;
  }

  @Override
  public CipherOutputStream getCipherOutputStream(
          OutputStream outputStream) throws CryptoManagerException
  {
    return getCipherOutputStream(preferredCipherTransformation,
            preferredCipherTransformationKeyLengthBits, outputStream);
  }

  @Override
  public CipherOutputStream getCipherOutputStream(
          String cipherTransformation, int keyLengthBits,
          OutputStream outputStream)
         throws CryptoManagerException
  {
    Reject.ifNull(cipherTransformation, outputStream);

    CipherKeyEntry keyEntry = getCipherKeyEntry(cipherTransformation, keyLengthBits);
    final Cipher cipher = getCipher(keyEntry, Cipher.ENCRYPT_MODE, null);
    final byte[] keyID = keyEntry.getKeyID().getByteValue();

    try {
      outputStream.write((byte)CIPHERTEXT_PROLOGUE_VERSION);
      outputStream.write(keyID);
      if (null != cipher.getIV()) {
        outputStream.write(cipher.getIV());
      }
    }
    catch (IOException ex) {
      logger.traceException(ex);
      throw new CryptoManagerException(
             ERR_CRYPTOMGR_GET_CIPHER_STREAM_PROLOGUE_WRITE_ERROR.get(
                     getExceptionMessage(ex)), ex);
    }

    return new CipherOutputStream(outputStream, cipher);
  }

  private CipherKeyEntry getCipherKeyEntry(String cipherTransformation, int keyLengthBits) throws CryptoManagerException
  {
    CipherKeyEntry keyEntry = CipherKeyEntry.getCipherKeyEntryOrNull(this, cipherTransformation, keyLengthBits);
    if (keyEntry == null) {
      cipherKeyEntryLock.lock();
      try
      {
        keyEntry = CipherKeyEntry.getCipherKeyEntryOrNull(this, cipherTransformation, keyLengthBits);
        if (keyEntry == null)
        {
          keyEntry = CipherKeyEntry.generateKeyEntry(this, cipherTransformation, keyLengthBits);
          mostRecentCipherKeys.put(getKeyFullSpec(cipherTransformation, keyLengthBits), keyEntry);
        }
      }
      finally
      {
        cipherKeyEntryLock.unlock();
      }
    }
    return keyEntry;
  }

  private String getKeyFullSpec(String transformation, int keyLength)
  {
    return transformation + "/" + keyLength;
  }

  @Override
  public byte[] decrypt(byte[] data)
         throws GeneralSecurityException,
                CryptoManagerException
  {
    int readIndex = 0;

    int version;
    try {
      version = data[readIndex++];
    }
    catch (Exception ex) {
      // IndexOutOfBoundsException, ArrayStoreException, ...
      logger.traceException(ex);
      throw new CryptoManagerException(
              ERR_CRYPTOMGR_DECRYPT_FAILED_TO_READ_PROLOGUE_VERSION.get(
                      ex.getMessage()), ex);
    }
    switch (version) {
      case CIPHERTEXT_PROLOGUE_VERSION:
        // Encryption key identifier only in the data prologue.
        break;

      default:
        throw new CryptoManagerException(
                ERR_CRYPTOMGR_DECRYPT_UNKNOWN_PROLOGUE_VERSION.get(version));
    }

    KeyEntryID keyID;
    try {
      final byte[] keyIDBytes
              = new byte[KeyEntryID.getByteValueLength()];
      System.arraycopy(data, readIndex, keyIDBytes, 0, keyIDBytes.length);
      readIndex += keyIDBytes.length;
      keyID = new KeyEntryID(keyIDBytes);
    }
    catch (Exception ex) {
      // IndexOutOfBoundsException, ArrayStoreException, ...
      logger.traceException(ex);
      throw new CryptoManagerException(
           ERR_CRYPTOMGR_DECRYPT_FAILED_TO_READ_KEY_IDENTIFIER.get(
                   ex.getMessage()), ex);
    }

    CipherKeyEntry keyEntry = CipherKeyEntry.getCipherKeyEntryOrNull(this, keyID);
    if (null == keyEntry) {
      throw new CryptoManagerException(
              ERR_CRYPTOMGR_DECRYPT_UNKNOWN_KEY_IDENTIFIER.get());
    }

    byte[] iv = null;
    if (0 < keyEntry.getIVLengthBits()) {
      iv = new byte[keyEntry.getIVLengthBits()/Byte.SIZE];
      try {
        System.arraycopy(data, readIndex, iv, 0, iv.length);
        readIndex += iv.length;
      }
      catch (Exception ex) {
        // IndexOutOfBoundsException, ArrayStoreException, ...
        logger.traceException(ex);
        throw new CryptoManagerException(
               ERR_CRYPTOMGR_DECRYPT_FAILED_TO_READ_IV.get(), ex);
      }
    }

    final Cipher cipher = getCipher(keyEntry, Cipher.DECRYPT_MODE, iv);
    if(data.length - readIndex > 0)
    {
      return cipher.doFinal(data, readIndex, data.length - readIndex);
    }
    else
    {
      // IBM Java 6 throws an IllegalArgumentException when there's no
      // data to process.
      return cipher.doFinal();
    }
  }

  @Override
  public CipherInputStream getCipherInputStream(
          InputStream inputStream) throws CryptoManagerException
  {
    int version;
    CipherKeyEntry keyEntry;
    byte[] iv = null;
    try {
      final byte[] rawVersion = new byte[1];
      if (rawVersion.length != inputStream.read(rawVersion)) {
        throw new CryptoManagerException(
                ERR_CRYPTOMGR_DECRYPT_FAILED_TO_READ_PROLOGUE_VERSION.get(
                      "stream underflow"));
      }
      version = rawVersion[0];
      switch (version) {
        case CIPHERTEXT_PROLOGUE_VERSION:
          // Encryption key identifier only in the data prologue.
          break;

        default:
          throw new CryptoManagerException(
                  ERR_CRYPTOMGR_DECRYPT_UNKNOWN_PROLOGUE_VERSION.get(version));
      }

      final byte[] keyID = new byte[KeyEntryID.getByteValueLength()];
      if (keyID.length != inputStream.read(keyID)) {
        throw new CryptoManagerException(
           ERR_CRYPTOMGR_DECRYPT_FAILED_TO_READ_KEY_IDENTIFIER.get(
                   "stream underflow"));
      }
      keyEntry = CipherKeyEntry.getCipherKeyEntryOrNull(this, new KeyEntryID(keyID));
      if (null == keyEntry) {
        throw new CryptoManagerException(
                ERR_CRYPTOMGR_DECRYPT_UNKNOWN_KEY_IDENTIFIER.get());
      }

      if (0 < keyEntry.getIVLengthBits()) {
        iv = new byte[keyEntry.getIVLengthBits() / Byte.SIZE];
        if (iv.length != inputStream.read(iv)) {
          throw new CryptoManagerException(
                  ERR_CRYPTOMGR_DECRYPT_FAILED_TO_READ_IV.get());
        }
      }
    }
    catch (IOException ex) {
      throw new CryptoManagerException(
             ERR_CRYPTOMGR_DECRYPT_CIPHER_INPUT_STREAM_ERROR.get(
                     getExceptionMessage(ex)), ex);
    }

    return new CipherInputStream(inputStream,
            getCipher(keyEntry, Cipher.DECRYPT_MODE, iv));
  }

  @Override
  public int compress(byte[] src, int srcOff, int srcLen,
                      byte[] dst, int dstOff, int dstLen)
  {
    Deflater deflater = new Deflater();
    try
    {
      deflater.setInput(src, srcOff, srcLen);
      deflater.finish();

      int compressedLength = deflater.deflate(dst, dstOff, dstLen);
      if (deflater.finished())
      {
        return compressedLength;
      }
      else
      {
        return -1;
      }
    }
    finally
    {
      deflater.end();
    }
  }

  @Override
  public int uncompress(byte[] src, int srcOff, int srcLen,
                        byte[] dst, int dstOff, int dstLen)
         throws DataFormatException
  {
    Inflater inflater = new Inflater();
    try
    {
      inflater.setInput(src, srcOff, srcLen);

      int decompressedLength = inflater.inflate(dst, dstOff, dstLen);
      if (inflater.finished())
      {
        return decompressedLength;
      }
      else
      {
        int totalLength = decompressedLength;

        while (! inflater.finished())
        {
          totalLength += inflater.inflate(dst, dstOff, dstLen);
        }

        return -totalLength;
      }
    }
    finally
    {
      inflater.end();
    }
  }

  @Override
  public SSLContext getSslContext(String componentName, SortedSet<String> sslCertNicknames) throws ConfigException
  {
    SSLContext sslContext;
    try
    {
      TrustStoreBackend trustStoreBackend = getTrustStoreBackend();
      KeyManager[] keyManagers = trustStoreBackend.getKeyManagers();
      TrustManager[] trustManagers =
           trustStoreBackend.getTrustManagers();

      sslContext = SSLContext.getInstance("TLS");

      if (sslCertNicknames == null)
      {
        sslContext.init(keyManagers, trustManagers, null);
      }
      else
      {
        KeyManager[] extendedKeyManagers =
            SelectableCertificateKeyManager.wrap(keyManagers, sslCertNicknames, componentName);
        sslContext.init(extendedKeyManagers, trustManagers, null);
      }
    }
    catch (Exception e)
    {
      logger.traceException(e);

      LocalizableMessage message =
           ERR_CRYPTOMGR_SSL_CONTEXT_CANNOT_INITIALIZE.get(
                getExceptionMessage(e));
      throw new ConfigException(message, e);
    }

    return sslContext;
  }

  @Override
  public SortedSet<String> getSslCertNicknames()
  {
    return sslCertNicknames;
  }

  @Override
  public boolean isSslEncryption()
  {
    return sslEncryption;
  }

  @Override
  public SortedSet<String> getSslProtocols()
  {
    return sslProtocols;
  }

  @Override
  public SortedSet<String> getSslCipherSuites()
  {
    return sslCipherSuites;
  }

  @Override
  public CryptoSuite newCryptoSuite(String cipherTransformation, int cipherKeyLength, boolean encrypt)
  {
    return new CryptoSuite(this, cipherTransformation, cipherKeyLength, encrypt);
  }
}
