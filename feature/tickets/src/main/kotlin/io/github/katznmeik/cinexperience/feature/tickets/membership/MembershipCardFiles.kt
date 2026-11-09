package io.github.katznmeik.cinexperience.feature.tickets.membership

import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.ticket.card.CardImageError
import io.github.katznmeik.cinexperience.core.ticket.card.CardImageResult
import io.github.katznmeik.cinexperience.core.ticket.card.MembershipCardImageReader

/** What a [CardImageReader] made of the screenshot (spec §11.4). */
sealed interface CardImageReadResult {
    /** The card's QR code as the model's [QrModules]. */
    data class Read(val qrModules: QrModules) : CardImageReadResult

    data class Refused(val error: CardImageError) : CardImageReadResult
}

/** Reads the membership card's screenshot ([DefaultCardImageReader] in the app, a fake in tests). Never throws for any content. */
fun interface CardImageReader {
    fun read(bytes: ByteArray): CardImageReadResult
}

/** The app's [CardImageReader]: core:ticket's [MembershipCardImageReader] with Android's image decoder. */
val DefaultCardImageReader: CardImageReader = CardImageReader { bytes -> MembershipCardImageReader().read(bytes).toReadResult() }

/** The checked grid as the model's [QrModules] (which accepts every grid the reader creates). */
internal fun CardImageResult.toReadResult(): CardImageReadResult = when (this) {
    is CardImageResult.Found -> try {
        CardImageReadResult.Read(QrModules(qrCode.size, qrCode.toBooleanArray()))
    } catch (e: IllegalArgumentException) {
        CardImageReadResult.Refused(CardImageError.NO_QR_CODE)
    }
    is CardImageResult.Refused -> CardImageReadResult.Refused(error)
}
