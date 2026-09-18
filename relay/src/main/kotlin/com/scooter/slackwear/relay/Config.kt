package com.scooter.slackwear.relay

data class Config(
    val port: Int,
    val slackSigningSecret: String,
    val firebaseCredentialsPath: String?,
    val stateDirectory: String,
) {
    companion object {
        fun fromEnvironment(): Config {
            val signingSecret = System.getenv("SLACK_SIGNING_SECRET")
            require(!signingSecret.isNullOrBlank()) {
                "SLACK_SIGNING_SECRET is required - without it the relay cannot tell a " +
                    "genuine Slack callback from anyone who found the URL."
            }

            return Config(
                port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
                slackSigningSecret = signingSecret,
                firebaseCredentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS"),
                stateDirectory = System.getenv("STATE_DIR") ?: "state",
            )
        }
    }
}
