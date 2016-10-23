package fi.bittiraha.walletd;

import org.bitcoinj.wallet.DefaultCoinSelector;
import org.bitcoinj.core.*;

public class ConfirmedCoinSelector extends DefaultCoinSelector {
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
        // Only accept transactions included in blocks.
        return type.equals(TransactionConfidence.ConfidenceType.BUILDING);
    }
}
