package com.tottodrillo.data.remote

import retrofit2.Response

/**
 * Rappresenta il risultato di un'operazione di rete
 */
sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(val exception: NetworkException) : NetworkResult<Nothing>()
    data object Loading : NetworkResult<Nothing>()
}

/**
 * Eccezioni personalizzate per errori di rete
 */
sealed class NetworkException(message: String) : Exception(message) {
    class NoInternet : NetworkException("Nessuna connessione internet disponibile")
    class Timeout : NetworkException("Timeout della richiesta")
    class ServerError(val code: Int, message: String) : NetworkException("Errore server ($code): $message")
    class ApiError(message: String) : NetworkException("Errore API: $message")
    class UnknownError(message: String) : NetworkException("Errore sconosciuto: $message")
    class SourceUnavailable(val sources: String) : NetworkException(sources)
    class ParseError : NetworkException("Errore nel parsing della risposta")
}

/**
 * Extension function per gestire le Response di Retrofit
 */
fun <T> Response<T>.toNetworkResult(): NetworkResult<T> {
    return try {
        if (isSuccessful) {
            body()?.let { data ->
                NetworkResult.Success(data)
            } ?: NetworkResult.Error(NetworkException.ParseError())
        } else {
            val errorMessage = errorBody()?.string() ?: "Errore sconosciuto"
            NetworkResult.Error(
                NetworkException.ServerError(code(), errorMessage)
            )
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        NetworkResult.Error(NetworkException.UnknownError(e.message ?: "Errore imprevisto"))
    }
}

/**
 * Helper per estrarre messaggi user-friendly dagli errori
 */
fun NetworkException.getUserMessage(): String {
    val context = com.tottodrillo.TottodrilloApp.instance
    fun text(id: Int, fallback: String): String = context?.getString(id) ?: fallback
    return when (this) {
        is NetworkException.NoInternet -> text(com.tottodrillo.R.string.network_no_internet, "Check your internet connection")
        is NetworkException.Timeout -> text(com.tottodrillo.R.string.network_timeout, "The request timed out")
        is NetworkException.ServerError -> context?.getString(com.tottodrillo.R.string.http_error, code) ?: "HTTP $code"
        is NetworkException.ParseError -> text(com.tottodrillo.R.string.network_parse, "Cannot read the server response")
        is NetworkException.SourceUnavailable -> context?.getString(com.tottodrillo.R.string.sources_search_failed, sources) ?: "Source search failed: $sources"
        is NetworkException.ApiError, is NetworkException.UnknownError -> text(com.tottodrillo.R.string.error_unknown, "An error occurred")
    }
}
