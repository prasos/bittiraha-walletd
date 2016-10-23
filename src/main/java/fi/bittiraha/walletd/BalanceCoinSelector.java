package fi.bittiraha.walletd;

import org.bitcoinj.wallet.DefaultCoinSelector;
import org.bitcoinj.core.*;

public class BalanceCoinSelector extends DefaultCoinSelector {
    @Override
    protected boolean shouldSelect(Transaction tx) {
        if (tx != null) {
            return isSelectable(tx);
        }
        return true;
    }
    
    public static boolean isSelectable(Transaction tx) {
        // Only pick chain-included transactions, or transactions that are ours and pending.
        TransactionConfidence confidence = tx.getConfidence();
        TransactionConfidence.ConfidenceType type = confidence.getConfidenceType();
        // BalanceCoinSelector doesn't require another peer to have seen the transaction
        // prior to selecting it, unlike DefaultCoinSelector. This is useful for calculating
        // wallet balance reliably.
        return type.equals(TransactionConfidence.ConfidenceType.BUILDING) ||
               type.equals(TransactionConfidence.ConfidenceType.PENDING) &&
               confidence.getSource().equals(TransactionConfidence.Source.SELF);
    }
}
