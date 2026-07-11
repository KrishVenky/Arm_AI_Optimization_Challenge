package com.audimus.consent

/**
 * Where the per-call consent handshake is. Audimus speaks a disclosure to the caller and listens
 * for a spoken yes/no in the transcript before it begins scam analysis.
 */
enum class ConsentState {
    /** No call / not started. */
    IDLE,

    /** Disclosure is playing; waiting for a spoken yes/no. */
    REQUESTING,

    /** Caller agreed — analysis may proceed. */
    CONSENTED,

    /** Caller refused — soft-stop, no analysis. */
    DECLINED,

    /** No clear answer within the timeout — soft-stop. */
    NO_RESPONSE,
}
