package com.frostbyte.freezer.adb;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Date;
import java.util.Locale;

public final class AdbKeyManager {
    private static final String TAG = "AdbKeyManager";
    private static final String PREF_NAME = "adb_pairing_key";
    private static final String KEY_PRIVATE = "private_key_pkcs8";
    private static final String KEY_CERT = "certificate_der";
    private static final long NOT_AFTER_MILLIS = 1956528000000L;
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;
    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    public AdbKeyManager(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        X509Certificate loadedCertificate = null;
        PrivateKey loadedPrivate = null;
        try {
            String privateBytes = preferences.getString(KEY_PRIVATE, null);
            String certBytes = preferences.getString(KEY_CERT, null);
            if (privateBytes != null && certBytes != null) {
                KeyFactory factory = KeyFactory.getInstance("RSA");
                loadedPrivate = factory.generatePrivate(new PKCS8EncodedKeySpec(
                        Base64.decode(privateBytes, Base64.NO_WRAP)));
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                loadedCertificate = (X509Certificate) certificateFactory.generateCertificate(
                        new ByteArrayInputStream(Base64.decode(certBytes, Base64.NO_WRAP)));
            }
        } catch (Throwable e) {
            Log.w(TAG, "stored adb key is unusable, regenerating", e);
        }

        if (loadedPrivate == null || loadedCertificate == null) {
            synchronized (LOCK) {
                try {
                    String privateBytes = preferences.getString(KEY_PRIVATE, null);
                    String certBytes = preferences.getString(KEY_CERT, null);
                    if (privateBytes != null && certBytes != null) {
                        KeyFactory factory = KeyFactory.getInstance("RSA");
                        loadedPrivate = factory.generatePrivate(new PKCS8EncodedKeySpec(
                                Base64.decode(privateBytes, Base64.NO_WRAP)));
                        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                        loadedCertificate = (X509Certificate) certificateFactory.generateCertificate(
                                new ByteArrayInputStream(Base64.decode(certBytes, Base64.NO_WRAP)));
                    }
                } catch (Throwable ignored) {
                }
                if (loadedPrivate == null || loadedCertificate == null) {
                    Generated generated = generate();
                    loadedPrivate = generated.privateKey;
                    loadedCertificate = generated.certificate;
                    try {
                        preferences.edit()
                                .putString(KEY_PRIVATE, Base64.encodeToString(
                                        loadedPrivate.getEncoded(), Base64.NO_WRAP))
                                .putString(KEY_CERT, Base64.encodeToString(
                                        loadedCertificate.getEncoded(), Base64.NO_WRAP))
                                .commit();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        privateKey = loadedPrivate;
        certificate = loadedCertificate;
        Log.d(TAG, "adb key ready: " + certificate.getSubjectX500Principal());
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public X509Certificate getCertificate() {
        return certificate;
    }

    public static boolean hasStoredKey(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PRIVATE, null) != null;
    }

    private static Generated generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
            java.security.KeyPair keyPair = generator.generateKeyPair();

            X500Name name = new X500Name("CN=00");
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .build(keyPair.getPrivate());
            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    name,
                    BigInteger.ONE,
                    new Date(0L),
                    new Date(NOT_AFTER_MILLIS),
                    Locale.ROOT,
                    name,
                    SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()));
            byte[] certificateBytes = builder.build(signer).getEncoded();
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) certificateFactory
                    .generateCertificate(new ByteArrayInputStream(certificateBytes));
            return new Generated(keyPair.getPrivate(), certificate);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate ADB key", e);
        }
    }

    private static final class Generated {
        final PrivateKey privateKey;
        final X509Certificate certificate;

        Generated(PrivateKey privateKey, X509Certificate certificate) {
            this.privateKey = privateKey;
            this.certificate = certificate;
        }
    }
}
