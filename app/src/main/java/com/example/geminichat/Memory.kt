package com.example.geminichat

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.VectorDistanceType

@Entity
data class Memory(
    @Id var id: Long = 0,
    var text: String = "",
    var role: String = "", // "user" or "model"
    var timestamp: Long = System.currentTimeMillis(),
    
    // text-embedding-004 output is 768 dimensions
    @HnswIndex(dimensions = 768, distanceType = VectorDistanceType.COSINE)
    var embedding: FloatArray? = null
)
