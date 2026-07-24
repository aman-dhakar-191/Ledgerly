package com.amandhakar.ledgerly.database

import androidx.room.TypeConverter
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.AuditReason
import com.amandhakar.ledgerly.database.entity.BalanceAnchorSource
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.ParserTxnType
import com.amandhakar.ledgerly.database.entity.SenderType
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.model.money.Paise

/** Money is never Double/Float/BigDecimal in storage — Paise round-trips to a plain Long column. */
class Converters {
    @TypeConverter fun paiseToLong(value: Paise): Long = value.value
    @TypeConverter fun longToPaise(value: Long): Paise = Paise(value)

    @TypeConverter fun parseStatusToString(value: ParseStatus): String = value.name
    @TypeConverter fun stringToParseStatus(value: String): ParseStatus = ParseStatus.valueOf(value)

    @TypeConverter fun senderTypeToString(value: SenderType): String = value.name
    @TypeConverter fun stringToSenderType(value: String): SenderType = SenderType.valueOf(value)

    @TypeConverter fun parserTxnTypeToString(value: ParserTxnType): String = value.name
    @TypeConverter fun stringToParserTxnType(value: String): ParserTxnType = ParserTxnType.valueOf(value)

    @TypeConverter fun accountTypeToString(value: AccountType): String = value.name
    @TypeConverter fun stringToAccountType(value: String): AccountType = AccountType.valueOf(value)

    @TypeConverter fun directionToString(value: Direction): String = value.name
    @TypeConverter fun stringToDirection(value: String): Direction = Direction.valueOf(value)

    @TypeConverter fun transactionSourceToString(value: TransactionSource): String = value.name
    @TypeConverter fun stringToTransactionSource(value: String): TransactionSource = TransactionSource.valueOf(value)

    @TypeConverter fun transactionStatusToString(value: TransactionStatus): String = value.name
    @TypeConverter fun stringToTransactionStatus(value: String): TransactionStatus = TransactionStatus.valueOf(value)

    @TypeConverter fun auditReasonToString(value: AuditReason): String = value.name
    @TypeConverter fun stringToAuditReason(value: String): AuditReason = AuditReason.valueOf(value)

    @TypeConverter fun balanceAnchorSourceToString(value: BalanceAnchorSource): String = value.name
    @TypeConverter fun stringToBalanceAnchorSource(value: String): BalanceAnchorSource = BalanceAnchorSource.valueOf(value)
}
