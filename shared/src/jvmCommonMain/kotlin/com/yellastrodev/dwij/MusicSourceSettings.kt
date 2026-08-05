package com.yellastrodev.dwij

interface MusicSourceSettings {

    fun load(): HomeMusicSource

    fun save(source: HomeMusicSource)
}