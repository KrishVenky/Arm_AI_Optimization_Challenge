package com.audimus.data.calls

import android.content.Context
import com.audimus.data.AudimusDatabase
import com.audimus.model.RiskLevel
import kotlinx.coroutines.flow.Flow

/** Repository over the finished-call log shown on the dashboard. */
class CallRepository(private val dao: CallRecordDao) {

    constructor(context: Context) : this(AudimusDatabase.get(context).callRecordDao())

    fun recent(): Flow<List<CallRecord>> = dao.recent()

    suspend fun record(label: String, risk: RiskLevel, reason: String, durationSec: Int): Long =
        dao.insert(
            CallRecord(
                label = label,
                riskLevel = risk.name,
                reason = reason,
                durationSec = durationSec,
                timestampMs = System.currentTimeMillis(),
            ),
        )
}
