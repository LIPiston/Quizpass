package top.lipiston.quizpass.data.type

enum class ServerToClientRequestType(
    val type: Int
) {
    FAILURE(-1),
    COMMAND_RESPONSE(0),
    AUTH_RESPONSE(3);

    companion object {
        fun of(requestType: Int): ServerToClientRequestType {
            return when (requestType) {
                FAILURE.type -> FAILURE
                COMMAND_RESPONSE.type -> COMMAND_RESPONSE
                AUTH_RESPONSE.type -> AUTH_RESPONSE
                else -> throw IllegalArgumentException("Unrecognized request type $requestType")
            }
        }
    }
}