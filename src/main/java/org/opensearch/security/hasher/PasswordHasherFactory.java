/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.security.hasher;

import java.util.Set;

import org.opensearch.common.settings.Settings;
import org.opensearch.security.support.ConfigConstants;

import static org.opensearch.security.support.ConfigConstants.ARGON2;
import static org.opensearch.security.support.ConfigConstants.BCRYPT;
import static org.opensearch.security.support.ConfigConstants.PBKDF2;

public class PasswordHasherFactory {

    private static final Set<String> ALLOWED_BCRYPT_MINORS = Set.of("A", "B", "Y");

    public static PasswordHasher createPasswordHasher(Settings settings) {
        String algorithm = settings.get(
            ConfigConstants.SECURITY_PASSWORD_HASHING_ALGORITHM,
            ConfigConstants.SECURITY_PASSWORD_HASHING_ALGORITHM_DEFAULT
        );

        PasswordHasher passwordHasher;
        switch (algorithm.toLowerCase()) {
            case BCRYPT:
                passwordHasher = getBCryptHasher(settings);
                break;
            case PBKDF2:
                passwordHasher = getPBKDF2Hasher(settings);
                break;
            case ARGON2:
                passwordHasher = getArgon2Hasher(settings);
                break;
            default:
                throw new IllegalArgumentException(String.format("Password hashing algorithm '%s' not supported.", algorithm));
        }
        return passwordHasher;
    }

    private static PasswordHasher getBCryptHasher(Settings settings) {
        int rounds = settings.getAsInt(
            ConfigConstants.SECURITY_PASSWORD_HASHING_BCRYPT_ROUNDS,
            ConfigConstants.SECURITY_PASSWORD_HASHING_BCRYPT_ROUNDS_DEFAULT
        );
        String minor = settings.get(
            ConfigConstants.SECURITY_PASSWORD_HASHING_BCRYPT_MINOR,
            ConfigConstants.SECURITY_PASSWORD_HASHING_BCRYPT_MINOR_DEFAULT
        ).toUpperCase();

        if (rounds < 4 || rounds > 31) {
            throw new IllegalArgumentException(String.format("BCrypt rounds must be between 4 and 31. Got: %d", rounds));
        }
        if (!ALLOWED_BCRYPT_MINORS.contains(minor)) {
            throw new IllegalArgumentException(String.format("BCrypt minor must be 'A', 'B', or 'Y'. Got: %s", minor));
        }
        return new BCryptPasswordHasher(minor, rounds);
    }

    private static PasswordHasher getPBKDF2Hasher(Settings settings) {
        String pbkdf2Function = settings.get(
            ConfigConstants.SECURITY_PASSWORD_HASHING_PBKDF2_FUNCTION,
            ConfigConstants.SECURITY_PASSWORD_HASHING_PBKDF2_FUNCTION_DEFAULT
        ).toUpperCase();

        int iterations = settings.getAsInt(
            ConfigConstants.SECURITY_PASSWORD_HASHING_PBKDF2_ITERATIONS,
            ConfigConstants.SECURITY_PASSWORD_HASHING_PBKDF2_ITERATIONS_DEFAULT
        );
        int length = settings.getAsInt(
            ConfigConstants.SECURITY_PASSWORD_HASHING_PBKDF2_LENGTH,
            ConfigConstants.SECURITY_PASSWORD_HASHING_PBKDF2_LENGTH_DEFAULT
        );

        if (!pbkdf2Function.matches("SHA(1|224|256|384|512)")) {
            throw new IllegalArgumentException(
                String.format("PBKDF2 function must be one of SHA1, SHA224, SHA256, SHA384, or SHA512. Got: %s", pbkdf2Function)
            );
        }
        if (iterations <= 0) {
            throw new IllegalArgumentException(String.format("PBKDF2 iterations must be a positive integer. Got: %d", iterations));
        }
        if (length <= 0) {
            throw new IllegalArgumentException(String.format("PBKDF2 length must be a positive integer. Got: %d", length));
        }
        return new PBKDF2PasswordHasher(pbkdf2Function, iterations, length);
    }

    private static PasswordHasher getArgon2Hasher(Settings settings) {
        int memory = settings.getAsInt(
            ConfigConstants.SECURITY_PASSWORD_HASHING_ARGON2_MEMORY,
            ConfigConstants.SECURITY_PASSWORD_HASHING_ARGON2_MEMORY_DEFAULT
        );
        int iterations = settings.getAsInt(
            ConfigConstants.SECURITY_PASSWORD_HASHING_ARGON2_ITERATIONS,
            ConfigConstants.SECURITY_PASSWORD_HASHING_ARGON2_ITERATIONS_DEFAULT
        );
        int parallelism = settings.getAsInt(
            ConfigConstants.SECURITY_PASSWORD_HASHING_ARGON2_PARALLELISM,
            ConfigConstants.SECURITY_PASSWORD_HASHING_ARGON2_PARALLELISM_DEFAULT
        );
        int length = settings.getAsInt(
            ConfigConstants.SECURITY_PASSWORD_HASHING_ARGON2_LENGTH,
            ConfigConstants.SECURITY_PASSWORD_HASHING_ARGON2_LENGTH_DEFAULT
        );
        String type = settings.get(
            ConfigConstants.SECURITY_PASSWORD_HASHING_ARGON2_TYPE,
            ConfigConstants.SECURITY_PASSWORD_HASHING_ARGON2_TYPE_DEFAULT
        );
        int version = settings.getAsInt(
            ConfigConstants.SECURITY_PASSWORD_HASHING_ARGON2_VERSION,
            ConfigConstants.SECURITY_PASSWORD_HASHING_ARGON2_VERSION_DEFAULT
        );

        if (memory <= 0) {
            throw new IllegalArgumentException(String.format("Argon2 memory must be a positive integer. Got: %d", memory));
        }
        if (iterations <= 0) {
            throw new IllegalArgumentException(String.format("Argon2 iterations must be a positive integer. Got: %d", iterations));
        }
        if (parallelism <= 0) {
            throw new IllegalArgumentException(String.format("Argon2 parallelism must be a positive integer. Got: %d", parallelism));
        }
        if (length <= 0) {
            throw new IllegalArgumentException(String.format("Argon2 length must be a positive integer. Got: %d", length));
        }
        String typeLower = type.toLowerCase();
        if (!typeLower.equals("argon2id") && !typeLower.equals("argon2i") && !typeLower.equals("argon2d")) {
            throw new IllegalArgumentException(String.format("Argon2 type must be one of argon2id, argon2i, or argon2d. Got: %s", type));
        }
        if (version != 16 && version != 19) {
            throw new IllegalArgumentException(String.format("Argon2 version must be either 16 or 19. Got: %d", version));
        }
        return new Argon2PasswordHasher(memory, iterations, parallelism, length, type, version);
    }
}
