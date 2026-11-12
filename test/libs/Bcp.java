import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.util.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;
public class Bcp {
  public static void main(String[] a) throws Exception {
    Security.addProvider(new BouncyCastleProvider());
    Provider p = Security.getProvider("BC");
    System.out.println(p.getName() + " " + p.getVersionStr() + " " + (Security.getProviders().length > 10));
    System.out.println(Hex.toHexString(MessageDigest.getInstance("SHA3-256", "BC").digest("champaca".getBytes())));
    System.out.println(Hex.toHexString(MessageDigest.getInstance("BLAKE2B-256", "BC").digest("champaca".getBytes())));
    SecretKeySpec key = new SecretKeySpec(Hex.decode("000102030405060708090a0b0c0d0e0f"), "AES");
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding", "BC");
    c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, new byte[12]));
    c.updateAAD("hdr".getBytes());
    byte[] ct = c.doFinal("hello bouncy castle".getBytes());
    System.out.println(Hex.toHexString(ct));
    c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, new byte[12])); c.updateAAD("hdr".getBytes());
    System.out.println(new String(c.doFinal(ct)));
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC", "BC");
    g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom(new byte[] {1}));
    KeyPair kp = g.generateKeyPair();
    Signature s = Signature.getInstance("SHA256withECDSA", "BC"); s.initSign(kp.getPrivate()); s.update("msg".getBytes()); byte[] sig = s.sign();
    s.initVerify(kp.getPublic()); s.update("msg".getBytes()); System.out.println("ecdsa verify " + s.verify(sig) + " " + kp.getPublic().getAlgorithm() + " " + kp.getPublic().getFormat());
    Signature js = Signature.getInstance("SHA256withECDSA", "SunEC"); js.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(kp.getPublic().getEncoded()))); js.update("msg".getBytes());
    System.out.println("verified by SunEC " + js.verify(sig));
    Mac mac = Mac.getInstance("HMacSHA512", "BC"); mac.init(new SecretKeySpec("k".getBytes(), "HMacSHA512"));
    System.out.println(Hex.toHexString(mac.doFinal("data".getBytes())).substring(0, 32));
    SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", "BC");
    System.out.println(Hex.toHexString(f.generateSecret(new PBEKeySpec("pw".toCharArray(), "salt".getBytes(), 1000, 128)).getEncoded()));
  }
}
