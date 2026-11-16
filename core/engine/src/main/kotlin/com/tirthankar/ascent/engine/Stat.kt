package com.tirthankar.ascent.engine

/** The six attributes. Each one grows from activity in its realm. */
enum class Stat(val realm: Realm) {
    STR(Realm.BODY),
    AGI(Realm.BODY),
    INT(Realm.MIND),
    FOC(Realm.MIND),
    WIS(Realm.SELF),
    VIT(Realm.SELF),
}
