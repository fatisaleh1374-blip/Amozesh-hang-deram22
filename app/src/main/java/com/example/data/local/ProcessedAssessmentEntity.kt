package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "processed_assessments")
data class ProcessedAssessmentEntity(
    @PrimaryKey val sessionId: String
)