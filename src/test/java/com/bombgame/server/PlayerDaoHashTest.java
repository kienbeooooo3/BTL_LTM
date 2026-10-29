package com.bombgame.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho phần băm mật khẩu của {@link PlayerDao}.
 * Không cần MySQL - chỉ kiểm tra logic thuần.
 */
class PlayerDaoHashTest {

    @Test
    @DisplayName("salt sinh ra phải khác nhau mỗi lần")
    void saltIsRandom() {
        String a = PlayerDao.newSalt();
        String b = PlayerDao.newSalt();
        assertNotEquals(a, b, "salt phải ngẫu nhiên");
        assertEquals(32, a.length(), "salt hex 32 ký tự");
        assertTrue(a.matches("^[0-9a-f]{32}$"), "salt phải là hex");
    }

    @Test
    @DisplayName("hash(salt, pw) ổn định và phụ thuộc cả salt lẫn mật khẩu")
    void hashDependsOnSaltAndPassword() {
        String salt = PlayerDao.newSalt();
        String h1 = PlayerDao.hash(salt, "matkhau");
        String h2 = PlayerDao.hash(salt, "matkhau");
        assertEquals(h1, h2, "cùng salt + cùng mật khẩu -> cùng hash");
        assertNotEquals(h1, PlayerDao.hash(salt, "matkhau2"), "mật khẩu khác -> hash khác");
        assertNotEquals(h1, PlayerDao.hash(PlayerDao.newSalt(), "matkhau"), "salt khác -> hash khác");
        assertEquals(64, h1.length(), "SHA-256 hex = 64 ký tự");
    }

    @Test
    @DisplayName("hai tài khoản cùng mật khẩu vẫn có hash khác nhau (chống rainbow table)")
    void samePasswordDifferentSaltDifferentHash() {
        String h1 = PlayerDao.hash(PlayerDao.newSalt(), "123456");
        String h2 = PlayerDao.hash(PlayerDao.newSalt(), "123456");
        assertNotEquals(h1, h2, "cùng mật khẩu nhưng salt khác -> hash khác");
    }

    @Test
    @DisplayName("verify nhận diện đúng scheme và trả lý do khi sai")
    void verifyDetectsAlgo() {
        String salt = PlayerDao.newSalt();
        String hash = PlayerDao.hash(salt, "secret");
        assertNull(PlayerDao.verify(PlayerDao.ALGO_SALT, hash, salt, null, "secret"),
                "mật khẩu đúng -> null (không lỗi)");
        assertNotNull(PlayerDao.verify(PlayerDao.ALGO_SALT, hash, salt, null, "sai"),
                "mật khẩu sai -> có lý do");
    }

    @Test
    @DisplayName("verify scheme cũ (không salt) vẫn hoạt động")
    void verifyLegacyNoSalt() {
        String legacy = PlayerDao.hashLegacy("oldpass");
        assertNull(PlayerDao.verify(PlayerDao.ALGO_PLAIN, legacy, "", "", "oldpass"));
        assertNotNull(PlayerDao.verify(PlayerDao.ALGO_PLAIN, legacy, "", "", "x"));
    }

    @Test
    @DisplayName("verify scheme cũ (salt Base64) vẫn hoạt động")
    void verifyLegacyBase64Salt() throws Exception {
        byte[] saltBytes = new byte[16];
        new java.security.SecureRandom().nextBytes(saltBytes);
        String saltB64 = Base64.getEncoder().encodeToString(saltBytes);

        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        md.update(saltBytes);
        String hashB64 = Base64.getEncoder().encodeToString(
                md.digest("legacyb64".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertNull(PlayerDao.verify(PlayerDao.ALGO_SALT_B64, hashB64, null, saltB64, "legacyb64"));
        assertNotNull(PlayerDao.verify(PlayerDao.ALGO_SALT_B64, hashB64, null, saltB64, "sai"));
    }

    @Test
    @DisplayName("hash_algo rỗng -> tự nhận diện scheme theo dạng dữ liệu")
    void verifyAutoDetectsAlgo() {
        String salt = PlayerDao.newSalt();
        String hash = PlayerDao.hash(salt, "auto");
        // hash_algo rỗng, có password_salt -> coi là sha256salt
        assertNull(PlayerDao.verify("", hash, salt, null, "auto"));

        String legacy = PlayerDao.hashLegacy("auto2");
        // hash_algo rỗng, không salt -> coi là sha256
        assertNull(PlayerDao.verify("", legacy, "", "", "auto2"));
    }

    @Test
    @DisplayName("scheme không hỗ trợ phải bị từ chối rõ ràng, không ném exception")
    void verifyRejectsUnknownAlgo() {
        String r = PlayerDao.verify("bcrypt99", "x", "", "", "pw");
        assertNotNull(r);
        assertTrue(r.contains("không được hỗ trợ"), "thông báo phải nói rõ scheme: " + r);
    }

    @Test
    @DisplayName("hash phải là hex hợp lệ")
    void hashIsHex() {
        String h = PlayerDao.hash("abcd", "x");
        assertTrue(HexFormat.of().parseHex(h).length == 32, "32 byte");
    }
}
