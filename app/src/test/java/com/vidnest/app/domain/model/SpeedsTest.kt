package com.vidnest.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedsTest {

    @Test
    fun `编解码往返一致`() {
        val options = listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f)
        assertEquals(options, Speeds.decode(Speeds.encode(options)))
    }

    @Test
    fun `兼容带x后缀的旧数据`() {
        assertEquals(
            listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f),
            Speeds.decode("0.5x,1.0x,1.5x,2.0x,2.5x,3.0x"),
        )
    }

    @Test
    fun `空或非法数据回落默认`() {
        assertEquals(Speeds.DEFAULT, Speeds.decode(null))
        assertEquals(Speeds.DEFAULT, Speeds.decode(""))
        assertEquals(Speeds.DEFAULT, Speeds.decode("abc,def"))
    }

    @Test
    fun `全集之外的值被过滤并升序去重`() {
        assertEquals(
            listOf(1.0f, 2.0f, 3.0f),
            Speeds.decode("3.0,1.0,2.0,9.9"),
        )
    }
}
