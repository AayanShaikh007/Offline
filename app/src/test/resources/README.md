# Loopback TLS fixture

`loopback-test.p12` contains a public, disposable self-signed test key/certificate for `AudioTransportTest`. The password is `test-only-password`; this is intentionally checked-in test material, not an account or release-signing credential. Its only subject alternative name is `localhost`, so using `127.0.0.1` exercises hostname rejection. Validity runs from 2020-01-01 through 2119-12-08 to avoid periodic fixture renewal.

The positive client trusts this certificate through a connection-specific SSLContext. Negative cases use default trust or the wrong hostname. No global trust store, hostname verifier, production certificate, or app network policy is modified. The certificate is under `src/test/resources` and is shared into instrumentation APK assets for native Android checks. It is excluded from the main application APK.

To regenerate with JDK 17, create a new file (keytool will not replace an existing alias):

```sh
keytool -genkeypair -alias loopback -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore loopback-test.p12 \
  -storepass test-only-password -keypass test-only-password \
  -dname 'CN=localhost,OU=Public test fixture,O=Offline' \
  -ext SAN=dns:localhost -startdate '2020/01/01 00:00:00' \
  -validity 36500 -noprompt
```
