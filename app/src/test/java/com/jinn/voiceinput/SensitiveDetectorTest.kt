package com.jinn.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 敏感内容检测单测（纯 JVM，不依赖 Android）。
 */
class SensitiveDetectorTest {

    @Test
    fun detectsVerifyCode() {
        assertNotNull("验证码应被识别", SensitiveDetector.detect("您的验证码是 482913，5 分钟内有效"))
        assertNotNull("独立 6 位数字 + 验证码语境应被识别", SensitiveDetector.detect("验证码：123456"))
        assertNotNull("登录场景 6 位码应被识别", SensitiveDetector.detect("动态码为 888666"))
    }

    @Test
    fun detectsIdCard() {
        assertNotNull("18 位身份证应被识别", SensitiveDetector.detect("身份证号 110105199003078915"))
        assertNotNull("18 位身份证（X 结尾）应被识别", SensitiveDetector.detect("证件号码 11010519900307891X"))
    }

    @Test
    fun detectsBankCard() {
        assertNotNull("16 位银行卡应被识别", SensitiveDetector.detect("6222020200112233445"))
        assertNotNull("带空格分组银行卡应被识别", SensitiveDetector.detect("6222 0202 0011 2233"))
    }

    @Test
    fun detectsApiKeyAndToken() {
        assertNotNull("api_key= 应被识别", SensitiveDetector.detect("api_key = sk-abcdefghijklmnopqrstuvwxyz"))
        assertNotNull("bearer token 应被识别", SensitiveDetector.detect("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop"))
    }

    @Test
    fun detectsJwt() {
        assertNotNull("JWT 三段式应被识别", SensitiveDetector.detect("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"))
    }

    @Test
    fun detectsPrivateKeyAndPassword() {
        assertNotNull("PEM 私钥应被识别", SensitiveDetector.detect("-----BEGIN RSA PRIVATE KEY-----\nMIIEpQIBAAKCAQEA"))
        assertNotNull("password= 应被识别", SensitiveDetector.detect("password=MyP@ssw0rd123"))
        assertNotNull("密码： 应被识别", SensitiveDetector.detect("密码：hunter2secret"))
    }

    @Test
    fun detectsCookie() {
        assertNotNull("sessionid cookie 应被识别", SensitiveDetector.detect("sessionid=abc123def456ghi789jkl"))
    }

    @Test
    fun normalTextNotDetected() {
        assertNull("普通中文不应被识别", SensitiveDetector.detect("今天天气很好，我们去公园散步"))
        assertNull("普通英文不应被识别", SensitiveDetector.detect("Hello world, this is a normal sentence."))
        assertNull("电话号码不应误报为敏感", SensitiveDetector.detect("我的电话是 13800138000"))
        assertNull("空白不应被识别", SensitiveDetector.detect(""))
        assertNull("null 不应被识别", SensitiveDetector.detect("   "))
    }

    @Test
    fun shortLivedCredentialClassification() {
        val verify = SensitiveDetector.detect("验证码 123456")
        assertNotNull(verify)
        assertTrue("验证码属于短时凭证", verify!!.let { SensitiveDetector.isShortLivedCredential(it) })

        val pwd = SensitiveDetector.detect("password=abc123456")
        assertNotNull(pwd)
        assertTrue("密码属于短时凭证", pwd!!.let { SensitiveDetector.isShortLivedCredential(it) })

        val id = SensitiveDetector.detect("110105199003078915")
        assertNotNull(id)
        assertEquals("身份证不属于短时凭证", false, id!!.let { SensitiveDetector.isShortLivedCredential(it) })
    }
}
