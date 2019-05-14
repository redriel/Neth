package com.example.neth2;

import java.io.File;
import java.math.BigDecimal;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import org.web3j.crypto.Credentials;
import org.web3j.crypto.WalletUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetBalance;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.core.methods.response.Web3ClientVersion;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.Transfer;
import org.web3j.utils.Convert;

/**
 *
 * @author: Gabriele Lavorato
 * This app can establish a connection to the Ethereum blockchain, can create an offline wallet as a JSON file and send ether via transaction to a given address.
 */

public class MainActivity extends AppCompatActivity {

    public static final String WALLET_NAME_KEY = "WALLET_NAME_KEY";
    public static final String WALLET_DIRECTORY_KEY = "WALLET_DIRECTORY_KEY";

    private final String password = "medium";
    private Web3j web3;
    private String attempt;
    private String walletPath;
    private File walletDirectory;
    private String walletName;
    private Button connectButton;
    private Button createWalletButton;
    private Button transactionHistoryButton;
    private SharedPreferences sharedPreferences;

    ListView listView;
    ArrayList<String> walletList;
    WalletAdapter listAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setupBouncyCastle();
        walletList = new ArrayList<>();

        View listHeader = View.inflate(this, R.layout.activity_main_header, null);
        listView = (ListView) findViewById(R.id.listView);
        listView.addHeaderView(listHeader);
        connectButton = (Button) findViewById(R.id.btnConnect);
        createWalletButton = (Button) findViewById(R.id.btnCreateWallet);
        transactionHistoryButton = (Button) findViewById(R.id.btnTransactionHistory);

        listAdapter = new WalletAdapter(MainActivity.this, R.id.walletAlias);
        listView.setAdapter(listAdapter);

        walletPath = getFilesDir().getAbsolutePath();
        walletDirectory = new File(walletPath);
        sharedPreferences = this.getSharedPreferences("com.example.Neth2", Context.MODE_PRIVATE);
        String storedPath = sharedPreferences.getString(WALLET_DIRECTORY_KEY, "") +
                "/" + sharedPreferences.getString(WALLET_NAME_KEY, "");
        refreshList();
    }

    /**
     * This function create a connection to the Rinkeby testnet via Infura endpoint.
     * @param view
     */
    public void connectToEthNetwork(View view) {
        web3 = Web3j.build(new HttpService("https://rinkeby.infura.io/v3/0d1f2e6517af42d3aa3f1706f96b913e"));
        try {
            Web3ClientVersion clientVersion = web3.web3ClientVersion().sendAsync().get();
            if(!clientVersion.hasError()){
                toastAsync("Connected!");
                connectButton.setText("Connected to Ethereum");
                connectButton.setBackgroundColor(0xFF7CCC26);
            }
            else {
                toastAsync(clientVersion.getError().getMessage());
            }
        } catch (Exception e) {
            toastAsync(e.getMessage());
        }
    }

    /**
     * This function generates a JSON file wallet and stores on the phone physical storage.
     * @param view
     */
    public void createWallet(View view){
        try{
            walletName = WalletUtils.generateFullNewWalletFile(password, walletDirectory);
            sharedPreferences.edit().putString(WALLET_NAME_KEY, walletName).apply();
            sharedPreferences.edit().putString(WALLET_DIRECTORY_KEY, walletDirectory.getAbsolutePath()).apply();
            refreshList();

            toastAsync("Wallet created!");
        }
        catch (Exception e){
            toastAsync("ERROR:" + e.getMessage());
        }
    }

    /**
     * This function displays the wallet address on screen. This information is used for testing purposes and to check transactions on Rinkeby Etherscan
     * @param view
     */
    public void getAddress(View view){
        try{
            Credentials credentials = WalletUtils.loadCredentials(password, sharedPreferences.getString(WALLET_DIRECTORY_KEY, "") +
                    "/" + sharedPreferences.getString(WALLET_NAME_KEY, ""));
            //TODO show address
        }
        catch (Exception e){
            toastAsync("ERROR:" + e.getMessage());
        }
    }

    /**
     * This function accesses the wallet using a password and a path. Then it sends some ether to a fixed address.
     * @param view
     */
    public void sendTransaction(View view){
        try{
            if(attempt.equals(password)){
            Credentials credentials = WalletUtils.loadCredentials(password, sharedPreferences.getString(WALLET_DIRECTORY_KEY, "") +
                    "/" + sharedPreferences.getString(WALLET_NAME_KEY, ""));
            TransactionReceipt receipt = Transfer.sendFunds(web3,credentials,"0x31B98D14007bDEe637298086988A0bBd31184523",
                    new BigDecimal("0.0001"), Convert.Unit.ETHER).sendAsync().get();
            toastAsync("Transaction complete: " + receipt.getTransactionHash());
            //transactionHashTv.setText(receipt.getTransactionHash());
            //TODO: add transaction receipt in TRANSACTION page
            getBalance(credentials.getAddress());
            }
            else {
                toastAsync("Wrong password");
            }
        }
        catch (Exception e){
            toastAsync(e.getMessage());
        }
    }

    public void getBalance(String address){
        // send asynchronous requests to get balance
        EthGetBalance ethGetBalance = null;
        try {
            ethGetBalance = web3
                    .ethGetBalance(address, DefaultBlockParameterName.LATEST)
                    .sendAsync()
                    .get();
        } catch (Exception e) {
            toastAsync(e.getMessage());
        }

        BigDecimal etherValue = Convert.fromWei(ethGetBalance.getBalance().toString(), Convert.Unit.ETHER);
        //balanceTv.setText(etherValue.toString());
        //TODO: show prompt with eth balance
    }

    /**
     * Prototype function for setting a password.
     * @param view
     */
    public void setPassword(View view){
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View passView = getLayoutInflater().inflate(R.layout.password, null);
        EditText passwordEt = (EditText) passView.findViewById(R.id.etPassword);
        Button setPasswordBtn = (Button) passView.findViewById(R.id.btnLogin);
        setPasswordBtn.setOnClickListener(view1 -> {
            toastAsync("Password set.");
            attempt = passwordEt.getText().toString();
        });

        builder.setView(passView);
        builder.show();
    }

    /**
     * Setup Security provider that corrects some issues with the BuoncyCastle library.
     * @author: serso
     */
    private void setupBouncyCastle() {
        final Provider provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
        if (provider == null) {
            // Web3j will set up the provider lazily when it's first used.
            return;
        }
        if (provider.getClass().equals(BouncyCastleProvider.class)) {
            // BC with same package name, shouldn't happen in real life.
            return;
        }
        // Android registers its own BC provider. As it might be outdated and might not include
        // all needed ciphers, we substitute it with a known BC bundled in the app.
        // Android's BC has its package rewritten to "com.android.org.bouncycastle" and because
        // of that it's possible to have another BC implementation loaded in VM.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }

    /**
     * Toast function to display messages and errors. Mainly used for testing purposes.
     * @param message
     */
    public void toastAsync(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    public void refreshList(){
        File folder = new File(sharedPreferences.getString(WALLET_DIRECTORY_KEY, ""));
        for(File f : folder.listFiles())
        {
            if(!walletList.contains(f.getName()))
            walletList.add(f.getName());
        }
        if(listAdapter != null)
            listAdapter.notifyDataSetChanged();
    }

    public void deleteWallet(final String walletName) {
        AlertDialog alertDialog = new AlertDialog.Builder(this)
                .setTitle("Delete Wallet")
                .setMessage("Do you want to delete this wallet from the storage?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    File file = new File(sharedPreferences.getString(WALLET_DIRECTORY_KEY, "") +
                            "/" + walletName);
                    if (file.exists()) {
                        file.delete();
                    } else {
                        System.err.println(
                                "I cannot find '" + file + "' ('" + file.getAbsolutePath() + "')");
                    }
                    walletList.remove(walletName);
                    listAdapter.notifyDataSetChanged();
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create();
        alertDialog.show();
    }

    public class WalletAdapter extends ArrayAdapter<String> {

        public WalletAdapter(Context context, int textView) {
            super(context, textView);
        }

        @Override
        public int getCount() {
            return walletList.size();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View itemView = LayoutInflater.from(parent.getContext()).
                    inflate(R.layout.list_items, parent, false);

            final TextView walletTV = (TextView) itemView.findViewById(R.id.walletAlias);
            walletTV.setText(walletList.get(position));
            final Button address = (Button) itemView.findViewById(R.id.addressButton);
            address.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //TODO: show wallet address
                }
            });
            final Button transaction = (Button) itemView.findViewById(R.id.transactionButton);
            transaction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //TODO: prompt transaction request
                }
            });
            final Button ethBalance =  (Button) itemView.findViewById(R.id.ethBalanceButton);
            ethBalance.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //TODO: show wallet balance
                }
            });
            final Button deleteButton = (Button) itemView.findViewById(R.id.deleteButton);
            deleteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    deleteWallet(walletTV.getText().toString());
                }
            });

            return itemView;
        }

        @Override
        public String getItem(int position) {
            return walletList.get(position);
        }

    }

}
