package top.lipiston.quizpass.data.type

enum class ClientToServerRequestType(
    val type: Int
) {
    COMMAND(2),
    AUTH(3);

    companion object {
        fun of(requestType: Int): ClientToServerRequestType {
            return when (requestType) {
                COMMAND.type -> COMMAND
                AUTH.type -> AUTH
                else -> throw IllegalArgumentException("Unrecognized request type $requestType")
            }
        }
    }
}