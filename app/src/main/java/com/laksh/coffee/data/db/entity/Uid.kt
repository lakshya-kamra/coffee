package com.laksh.coffee.data.db.entity

/** A stable id that survives export and import across phones and the web app. */
fun newUid(): String = java.util.UUID.randomUUID().toString()
