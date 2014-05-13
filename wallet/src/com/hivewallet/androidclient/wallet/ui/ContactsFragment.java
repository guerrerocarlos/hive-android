package com.hivewallet.androidclient.wallet.ui;

import com.actionbarsherlock.app.SherlockFragment;
import com.google.bitcoin.core.AddressFormatException;
import com.hivewallet.androidclient.wallet.AddressBookProvider;
import com.hivewallet.androidclient.wallet.R;
import com.squareup.picasso.Picasso;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.SimpleCursorAdapter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

public class ContactsFragment extends SherlockFragment implements LoaderCallbacks<Cursor>
{
	private WalletActivity activity;
	
	private ListView contactsListView;
	private ImageButton addContactImageButton;
	
	private SimpleCursorAdapter contactsSimpleCursorAdapter;
	
	public static ContactsFragment instance()
	{
		return new ContactsFragment();
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.contacts_list_view, container, false);

		addContactImageButton = (ImageButton)view.findViewById(R.id.ib_add_contact);
		addContactImageButton.setOnClickListener(addContactOnClickListener);

		return view;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState)
	{
		super.onActivityCreated(savedInstanceState);

		final String[] from_columns = { AddressBookProvider.KEY_LABEL
									  , AddressBookProvider.KEY_PHOTO
									  , AddressBookProvider.KEY_ADDRESS
									  };
		final int[] to_ids = { R.id.tv_contact_name, R.id.iv_contact_photo, R.id.ib_contact_send_money };
		contactsSimpleCursorAdapter = new SimpleCursorAdapter
				( getActivity()
				, R.layout.contacts_list_item
				, null
				, from_columns
				, to_ids
				, 0
				);
		
		contactsSimpleCursorAdapter.setViewBinder(new SimpleCursorAdapter.ViewBinder()
		{
			@Override
			public boolean setViewValue(View view, Cursor cursor, int columnIndex)
			{
				switch (view.getId()) {
					case R.id.tv_contact_name:
						return setContactName((TextView)view, cursor, columnIndex);
					case R.id.iv_contact_photo:
						return setContactPhoto((ImageView)view, cursor, columnIndex);
					case R.id.ib_contact_send_money:
						return setSendMoneyButton((ImageButton)view, cursor, columnIndex);
					default:
						return false;
				}
			}
			
			private boolean setContactName(TextView textView, Cursor cursor, int columnIndex) {
				final String name = cursor.getString(columnIndex);
				final String address = cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_ADDRESS));
				textView.setText(name);
				setEditAction(textView, address);
				
				return true;
			}
			
			private boolean setContactPhoto(ImageView imageView, Cursor cursor, int columnIndex) {
				String photo = cursor.getString(columnIndex);
				final String address = cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_ADDRESS));
				Uri uri = null;
				if (photo != null)
					uri = Uri.parse(photo);
				
				Picasso.with(activity)
					.load(uri)
					.placeholder(R.drawable.ic_contact_picture)
					.into(imageView);
				
				setEditAction(imageView, address);
				
		    	return true;
			}
			
			private boolean setSendMoneyButton(ImageButton imageButton, Cursor cursor, int columnIndex) {
				final String address = cursor.getString(columnIndex);
				final String label = cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_LABEL));
				imageButton.setOnClickListener(new OnClickListener()
				{
					@Override
					public void onClick(View v)
					{
						try { 
							activity.handleSendCoinsToAddress(address, label);
						} catch (AddressFormatException e) {
							// if something is wrong with the address, ignore it
							// and go to the send coin screen anyway
							activity.handleSendCoins();
						}
					}
				});
				return true;
			}
			
			private void setEditAction(final View view, final String address) {
				view.setOnLongClickListener(new View.OnLongClickListener()
				{
					@Override
					public boolean onLongClick(View v)
					{
						EditAddressBookEntryFragment.edit(getFragmentManager(), address);
						return true;
					}
				});
			}
		});		
						
		contactsListView = (ListView) getActivity().findViewById(R.id.lv_contacts);
		contactsListView.setAdapter(contactsSimpleCursorAdapter);
		
		TextView noContactsTextView = (TextView) getActivity().findViewById(R.id.tv_no_contacts);
		contactsListView.setEmptyView(noContactsTextView);
		
		getLoaderManager().initLoader(0, null, this);
	}
	
	@Override
	public void onAttach(final Activity activity)
	{
		super.onAttach(activity);

		this.activity = (WalletActivity) activity;
	}
	
	private OnClickListener addContactOnClickListener = new OnClickListener()
	{
		@Override
		public void onClick(View v)
		{
			AddContactChoiceFragment.show(getFragmentManager());
		}
	};

	@Override
	public Loader<Cursor> onCreateLoader(int loaderId, Bundle args)
	{
		final Uri uri = AddressBookProvider.contentUri(getActivity().getPackageName());
		CursorLoader loader = new CursorLoader
				( getActivity()
				, uri
				, null
				, null
				, null
				, AddressBookProvider.KEY_LABEL + " COLLATE LOCALIZED ASC"
				);
		return loader;
	}

	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor cursor)
	{
		contactsSimpleCursorAdapter.swapCursor(cursor);
	}

	@Override
	public void onLoaderReset(Loader<Cursor> arg0)
	{
		contactsSimpleCursorAdapter.swapCursor(null);
	}
}
