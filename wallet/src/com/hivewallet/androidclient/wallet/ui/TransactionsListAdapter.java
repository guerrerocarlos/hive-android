/*
 * Copyright 2011-2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hivewallet.androidclient.wallet.ui;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.Uri;
import android.text.Html;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Transaction.Purpose;
import com.google.bitcoin.core.TransactionConfidence;
import com.google.bitcoin.core.TransactionConfidence.ConfidenceType;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.wallet.DefaultCoinSelector;
import com.google.common.base.Optional;

import com.hivewallet.androidclient.wallet.AddressBookProvider;
import com.hivewallet.androidclient.wallet.AddressBookProvider.AddressBookEntry;
import com.hivewallet.androidclient.wallet.Constants;
import com.hivewallet.androidclient.wallet.ExchangeRatesProvider.ExchangeRate;
import com.hivewallet.androidclient.wallet.util.CircularProgressView;
import com.hivewallet.androidclient.wallet.util.GenericUtils;
import com.hivewallet.androidclient.wallet.util.WalletUtils;
import com.hivewallet.androidclient.wallet.R;
import com.squareup.picasso.Picasso;

/**
 * @author Andreas Schildbach
 */
public class TransactionsListAdapter extends BaseAdapter
{
	private final Context context;
	private final LayoutInflater inflater;
	private final Wallet wallet;
	private final int maxConnectedPeers;

	private final List<Transaction> transactions = new ArrayList<Transaction>();
	private int precision = 0;
	private int shift = 0;
	private String currencyCode = "";
	private ExchangeRate exchangeRate = null;
	private boolean showEmptyText = false;
	private boolean showBackupWarning = false;

	private final int colorSignificant;
	private final int colorInsignificant;
	private final int colorError;
	private final int colorCircularBuilding = Color.parseColor("#44ff44");

	private final Map<String, Optional<AddressBookEntry>> addressBookCache = new HashMap<String, Optional<AddressBookEntry>>();
	
	private static final String CONFIDENCE_SYMBOL_DEAD = "\u271D"; // latin cross
	private static final String CONFIDENCE_SYMBOL_UNKNOWN = "?";

	private static final int VIEW_TYPE_TRANSACTION = 0;
	private static final int VIEW_TYPE_WARNING = 1;
	
	public TransactionsListAdapter(final Context context, @Nonnull final Wallet wallet, final int maxConnectedPeers, final boolean showBackupWarning)
	{
		this.context = context;
		inflater = LayoutInflater.from(context);

		this.wallet = wallet;
		this.maxConnectedPeers = maxConnectedPeers;
		this.showBackupWarning = showBackupWarning;

		final Resources resources = context.getResources();
		colorSignificant = resources.getColor(R.color.fg_significant);
		colorInsignificant = resources.getColor(R.color.fg_insignificant);
		colorError = resources.getColor(R.color.fg_error);
	}

	public void setPrecision(final int precision, final int shift)
	{
		this.precision = precision;
		this.shift = shift;

		notifyDataSetChanged();
	}
	
	public void setCurrencyCode(@Nonnull final String currencyCode)
	{
		this.currencyCode = currencyCode;
		
		notifyDataSetChanged();
	}
	
	public void setExchangeRate(@Nonnull final ExchangeRate exchangeRate) {
		this.exchangeRate = exchangeRate;
		
		notifyDataSetChanged();
	}

	public void clear()
	{
		transactions.clear();

		notifyDataSetChanged();
	}

	public void replace(@Nonnull final Transaction tx)
	{
		transactions.clear();
		transactions.add(tx);

		notifyDataSetChanged();
	}

	public void replace(@Nonnull final Collection<Transaction> transactions)
	{
		this.transactions.clear();
		this.transactions.addAll(transactions);

		showEmptyText = true;

		notifyDataSetChanged();
	}

	@Override
	public boolean isEmpty()
	{
		return showEmptyText && super.isEmpty();
	}

	@Override
	public int getCount()
	{
		int count = transactions.size();

		if (count == 1 && showBackupWarning)
			count++;

		return count;
	}

	@Override
	public Transaction getItem(final int position)
	{
		if (position == transactions.size() && showBackupWarning)
			return null;

		return transactions.get(position);
	}

	@Override
	public long getItemId(final int position)
	{
		if (position == transactions.size() && showBackupWarning)
			return 0;

		return WalletUtils.longHash(transactions.get(position).getHash());
	}

	@Override
	public int getViewTypeCount()
	{
		return 2;
	}

	@Override
	public int getItemViewType(final int position)
	{
		if (position == transactions.size() && showBackupWarning)
			return VIEW_TYPE_WARNING;
		else
			return VIEW_TYPE_TRANSACTION;
	}

	@Override
	public boolean hasStableIds()
	{
		return true;
	}

	@Override
	public View getView(final int position, View row, final ViewGroup parent)
	{
		final int type = getItemViewType(position);

		if (type == VIEW_TYPE_TRANSACTION)
		{
			if (row == null)
				row = inflater.inflate(R.layout.hive_transaction_row_extended, null);

			final Transaction tx = getItem(position);
			bindView(row, tx);
		}
		else if (type == VIEW_TYPE_WARNING)
		{
			if (row == null)
				row = inflater.inflate(R.layout.transaction_row_warning, null);

			final TextView messageView = (TextView) row.findViewById(R.id.transaction_row_warning_message);
			messageView.setText(Html.fromHtml(context.getString(R.string.wallet_transactions_row_warning_backup)));
		}
		else
		{
			throw new IllegalStateException("unknown type: " + type);
		}

		return row;
	}

	public void bindView(@Nonnull final View row, @Nonnull final Transaction tx)
	{
		final TransactionConfidence confidence = tx.getConfidence();
		final ConfidenceType confidenceType = confidence.getConfidenceType();
		final boolean isOwn = confidence.getSource().equals(TransactionConfidence.Source.SELF);
		final boolean isCoinBase = tx.isCoinBase();
		final boolean isInternal = WalletUtils.isInternal(tx);

		final BigInteger value = tx.getValue(wallet);
		final boolean sent = value.signum() < 0;

		final CircularProgressView rowConfidenceCircular = (CircularProgressView) row.findViewById(R.id.transaction_row_confidence_circular);
		final TextView rowConfidenceTextual = (TextView) row.findViewById(R.id.transaction_row_confidence_textual);

		// confidence
		if (confidenceType == ConfidenceType.PENDING)
		{
			rowConfidenceCircular.setVisibility(View.VISIBLE);
			rowConfidenceTextual.setVisibility(View.GONE);

			rowConfidenceCircular.setProgress(1);
			rowConfidenceCircular.setMaxProgress(1);
			rowConfidenceCircular.setSize(confidence.numBroadcastPeers());
			rowConfidenceCircular.setMaxSize(maxConnectedPeers / 2); // magic value
			rowConfidenceCircular.setColors(colorInsignificant, colorInsignificant);
		}
		else if (confidenceType == ConfidenceType.BUILDING)
		{
			final int depth = confidence.getDepthInBlocks();
			final int maxProgress = isCoinBase ? Constants.NETWORK_PARAMETERS.getSpendableCoinbaseDepth()
					: Constants.MAX_NUM_CONFIRMATIONS;
			
			if (depth <= maxProgress) {
				rowConfidenceCircular.setVisibility(View.VISIBLE);
				rowConfidenceTextual.setVisibility(View.GONE);
	
				rowConfidenceCircular.setProgress(depth);
				rowConfidenceCircular.setMaxProgress(maxProgress);
				rowConfidenceCircular.setSize(1);
				rowConfidenceCircular.setMaxSize(1);
				rowConfidenceCircular.setColors(colorCircularBuilding, Color.DKGRAY);
			} else {
				rowConfidenceCircular.setVisibility(View.GONE);
				rowConfidenceTextual.setVisibility(View.GONE);
			}
		}
		else if (confidenceType == ConfidenceType.DEAD)
		{
			rowConfidenceCircular.setVisibility(View.GONE);
			rowConfidenceTextual.setVisibility(View.VISIBLE);

			rowConfidenceTextual.setText(CONFIDENCE_SYMBOL_DEAD);
			rowConfidenceTextual.setTextColor(Color.RED);
		}
		else
		{
			rowConfidenceCircular.setVisibility(View.GONE);
			rowConfidenceTextual.setVisibility(View.VISIBLE);

			rowConfidenceTextual.setText(CONFIDENCE_SYMBOL_UNKNOWN);
			rowConfidenceTextual.setTextColor(colorInsignificant);
		}

		// spendability
		final int textColor;
		if (confidenceType == ConfidenceType.DEAD)
			textColor = Color.RED;
		else
			textColor = DefaultCoinSelector.isSelectable(tx) ? colorSignificant : colorInsignificant;

		// time
		final TextView rowTime = (TextView) row.findViewById(R.id.transaction_row_time);
		if (rowTime != null)
		{
			final Date time = tx.getUpdateTime();
			rowTime.setText(time != null ? (DateUtils.getRelativeTimeSpanString(context, time.getTime())) : null);
			rowTime.setTextColor(textColor);
		}

		// receiving or sending - symbols are reversed, to point to the contact picture
		final TextView rowFromTo = (TextView) row.findViewById(R.id.transaction_row_fromto);
		if (isInternal)
			rowFromTo.setText(R.string.symbol_internal);
		else if (sent)
			rowFromTo.setText(R.string.symbol_from);
		else
			rowFromTo.setText(R.string.symbol_to);
		rowFromTo.setTextColor(textColor);

		// coinbase
		final View rowCoinbase = row.findViewById(R.id.transaction_row_coinbase);
		rowCoinbase.setVisibility(isCoinBase ? View.VISIBLE : View.GONE);

		// address, if it can be identified
		final Address address = sent ? WalletUtils.getFirstToAddress(tx) : WalletUtils.getFirstFromAddress(tx);
		AddressBookEntry entry = null;
		String label = null;
		String suffixData = null;
		if (address != null) {
			entry = lookupEntry(address.toString());
			if (entry != null) label = entry.getLabel();
			suffixData = label != null ? label : GenericUtils.shortenString(address.toString());
		}
		
		// prepare tx msg
		final String prefix = context.getResources().getString(
				sent ? R.string.tx_msg_prefix_sent : R.string.tx_msg_prefix_received);
		String suffix = null;
		if (suffixData != null)
			suffix = context.getResources().getString(
					sent ? R.string.tx_msg_suffix_sent : R.string.tx_msg_suffix_received, suffixData);
		
		// prepare the display of amounts
		final CurrencyPlusInfoTextView rowValue = (CurrencyPlusInfoTextView) row.findViewById(R.id.transaction_row_value);
		rowValue.setTextColor(textColor);
		rowValue.setPrecision(precision, shift);
		rowValue.setExchangeRate(exchangeRate);
		rowValue.setValidExchangeRate(!Constants.TEST);
		rowValue.setPrefix(prefix);
		rowValue.setSuffix(suffix);
		rowValue.setUseBold(true);
		
		final CurrencyPlusInfoTextView rowFee = (CurrencyPlusInfoTextView) row.findViewById(R.id.transaction_row_fee);
		if (sent) {
			// show fee separately for outgoing transactions
			final BigInteger valueWithoutFee = WalletUtils.sumOfOutputs(tx).subtract(tx.getValueSentToMe(wallet));
			final BigInteger fee = value.abs().subtract(valueWithoutFee);
			
			rowValue.setAmount(valueWithoutFee, currencyCode);
			
			rowFee.setTextColor(textColor);
			rowFee.setPrecision(precision, shift);
			rowFee.setAmount(fee, currencyCode);
			rowFee.setExchangeRate(exchangeRate);
			rowFee.setValidExchangeRate(!Constants.TEST);
			rowFee.setPrefix(context.getResources().getString(R.string.tx_fee));
			rowFee.setVisibility(View.VISIBLE);
		} else {
			// otherwise just show the amount received
			rowValue.setAmount(value, currencyCode);
			
			rowFee.setVisibility(View.GONE);
		}

		// contact photo
		final ImageView rowContactPhoto = (ImageView) row.findViewById(R.id.transaction_row_contact_photo);
		Uri photoUri = null;
		if (entry != null) photoUri = entry.getPhotoUri();
		Picasso.with(context)
			.load(photoUri)
			.placeholder(R.drawable.ic_contact_picture)
			.into(rowContactPhoto);

		// extended message
		final View rowExtend = row.findViewById(R.id.transaction_row_extend);
		if (rowExtend != null)
		{
			final TextView rowMessage = (TextView) row.findViewById(R.id.transaction_row_message);
			final boolean isTimeLocked = tx.isTimeLocked();
			rowExtend.setVisibility(View.GONE);

			if (tx.getPurpose() == Purpose.KEY_ROTATION)
			{
				rowExtend.setVisibility(View.VISIBLE);
				rowMessage.setText(Html.fromHtml(context.getString(R.string.transaction_row_message_purpose_key_rotation)));
				rowMessage.setTextColor(colorSignificant);
			}
			else if (isOwn && confidenceType == ConfidenceType.PENDING && confidence.numBroadcastPeers() == 0)
			{
				rowExtend.setVisibility(View.VISIBLE);
				rowMessage.setText(R.string.transaction_row_message_own_unbroadcasted);
				rowMessage.setTextColor(colorInsignificant);
			}
			else if (!isOwn && confidenceType == ConfidenceType.PENDING && confidence.numBroadcastPeers() == 0)
			{
				rowExtend.setVisibility(View.VISIBLE);
				rowMessage.setText(R.string.transaction_row_message_received_direct);
				rowMessage.setTextColor(colorInsignificant);
			}
			else if (!sent && value.compareTo(Transaction.MIN_NONDUST_OUTPUT) < 0)
			{
				rowExtend.setVisibility(View.VISIBLE);
				rowMessage.setText(R.string.transaction_row_message_received_dust);
				rowMessage.setTextColor(colorInsignificant);
			}
			else if (!sent && confidenceType == ConfidenceType.PENDING && isTimeLocked)
			{
				rowExtend.setVisibility(View.VISIBLE);
				rowMessage.setText(R.string.transaction_row_message_received_unconfirmed_locked);
				rowMessage.setTextColor(colorError);
			}
			else if (!sent && confidenceType == ConfidenceType.PENDING && !isTimeLocked)
			{
				rowExtend.setVisibility(View.VISIBLE);
				rowMessage.setText(R.string.transaction_row_message_received_unconfirmed_unlocked);
				rowMessage.setTextColor(colorInsignificant);
			}
			else if (!sent && confidenceType == ConfidenceType.DEAD)
			{
				rowExtend.setVisibility(View.VISIBLE);
				rowMessage.setText(R.string.transaction_row_message_received_dead);
				rowMessage.setTextColor(colorError);
			}
		}
	}
	
	private AddressBookEntry lookupEntry(@Nonnull final String address)
	{
		final Optional<AddressBookEntry> cachedEntry = addressBookCache.get(address);
		if (cachedEntry == null)
		{
			final AddressBookEntry entry = AddressBookProvider.lookupEntry(context, address);
			final Optional<AddressBookEntry> optionalEntry = Optional.fromNullable(entry);
			addressBookCache.put(address, optionalEntry);	// cache entry or the fact that it wasn't found
			return entry;
		}
		else
		{
			return cachedEntry.orNull();
		}
	}

	public void clearAddressBookCache()
	{
		addressBookCache.clear();

		notifyDataSetChanged();
	}
}
