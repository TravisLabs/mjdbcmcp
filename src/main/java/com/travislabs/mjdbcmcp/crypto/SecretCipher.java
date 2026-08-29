package com.travislabs.mjdbcmcp.crypto;

import com.travislabs.mjdbcmcp.config.AppProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Properties;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for datasource passwords at rest. The key lives in
 * {@code ${config-dir}/secret.key}, generated on first boot with owner-only permissions — losing it
 * means every stored password has to be re-entered, which is the intended failure mode.
 */
@Component
public class SecretCipher {

    private final TextEncryptor encryptor;

    public SecretCipher(AppProperties props) {
        Properties keys = loadOrCreate(props.secretKeyFile());
        this.encryptor = Encryptors.delux(keys.getProperty("key"), keys.getProperty("salt"));
    }

    public String encrypt(String plaintext) {
        return plaintext == null || plaintext.isEmpty() ? null : encryptor.encrypt(plaintext);
    }

    public String decrypt(String ciphertext) {
        return ciphertext == null || ciphertext.isEmpty() ? null : encryptor.decrypt(ciphertext);
    }

    private static Properties loadOrCreate(Path file) {
        Properties props = new Properties();
        try {
            if (Files.exists(file)) {
                try (var in = Files.newInputStream(file)) {
                    props.load(in);
                }
                if (props.getProperty("key") == null || props.getProperty("salt") == null) {
                    throw new IllegalStateException("Malformed key file: " + file);
                }
                return props;
            }
            props.setProperty("key", KeyGenerators.string().generateKey() + KeyGenerators.string().generateKey());
            props.setProperty("salt", KeyGenerators.string().generateKey());
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                props.store(out, "mjdbcmcp datasource password encryption key - back this up, keep it secret");
            }
            trySetOwnerOnly(file);
            return props;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or create " + file, e);
        }
    }

    private static void trySetOwnerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and exotic filesystems: the file is still only as exposed as the config dir.
        }
    }
}
