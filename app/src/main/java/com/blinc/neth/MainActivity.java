package com.blinc.neth;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.web3j.crypto.CipherException;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.MnemonicUtils;
import org.web3j.crypto.WalletUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetBalance;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.core.methods.response.Web3ClientVersion;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.Transfer;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.utils.Convert;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.web3j.crypto.Hash.sha256;

/**
 * This app can:
 * - establish a connection to the Ethereum blockchain
 * - create an offline HD wallet as a JSON file
 * - send ether via transaction to a given address.
 * - call the SignUpRegistry contract
 * - show info of a given wallet, such as address and ether balance
 * - recover a lost wallet through a 12-words mnemonic phrase
 * - register the user to the contract
 * - upload a document hash as transaction input data
 * - list all the hash document the user uploaded
 *
 * @author Gabriele Lavorato
 * @version 0.4.1
 */

public class MainActivity extends AppCompatActivity {

    public static final String WALLET_DIRECTORY_KEY = "WALLET_DIRECTORY_KEY";

    //Default settings for contract gas price and gas limit
    public static final BigInteger gasPrice = DefaultGasProvider.GAS_PRICE;
    public static final BigInteger gasLimit = DefaultGasProvider.GAS_LIMIT;

    //You should use your own Infura Endpoint and contract address
    public static final String infuraEndpoint = "https://rinkeby.infura.io/v3/0d1f2e6517af42d3aa3f1706f96b913e";
    public static final String smartContractAddress = "0x81eB3DA8e7CC5519386BC50e70a8AaCFd935fFC1";

    final int ACTIVITY_CHOOSE_FILE = 1000;

    public static final Object sharedLock = new Object();
    public ListView listView;
    public ArrayList<String> walletList;
    public WalletAdapter listAdapter;
    public HashMap<String, Credentials> accounts = new HashMap<>();

    private static SharedPreferences sharedPreferences;
    private File fileToHash;
    private String password;
    private Web3j web3j;
    private File walletDirectory;
    private Button connectButton;
    private boolean connection;
    private ProgressBar progressBar;
    private Credentials credentials = null;

    /**
     * Compute a random value in a given range
     *
     * @param min: the lower bound
     * @param max: the upper bound
     * @return the random value
     */
    private static int getRandomNumberInRange(int min, int max) {
        if (min >= max) {
            throw new IllegalArgumentException("max must be greater than min");
        }
        Random r = new Random();
        return r.nextInt((max - min) + 1) + min;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle(R.string.activity_name);

        setupBouncyCastle();
        walletList = new ArrayList<>();
        connection = false;

        View listHeader = View.inflate(this, R.layout.activity_main_header, null);
        listView = findViewById(R.id.listView);
        listView.addHeaderView(listHeader);
        connectButton = findViewById(R.id.connectBtn);
        Button recoveryButton = findViewById(R.id.recoverWalletBtn);
        recoveryButton.setOnClickListener(view -> mnemonicRecovery());
        progressBar = findViewById(R.id.progressbar);
        progressBar.setVisibility(View.INVISIBLE);

        listAdapter = new WalletAdapter(MainActivity.this, R.id.walletAliasTV);
        listView.setAdapter(listAdapter);

        String walletPath = getFilesDir().getAbsolutePath();
        walletDirectory = new File(walletPath);
        sharedPreferences = this.getSharedPreferences("com.blinc.neth", Context.MODE_PRIVATE);
        refreshList();
    }

    /**
     * Establish connection to the Rinkeby testnet via Infura endpoint
     *
     * @param view: observed view
     */
    public void connectToEthNetwork(View view) {
        web3j = Web3j.build(new HttpService(infuraEndpoint));
        try {
            Web3ClientVersion clientVersion = web3j.web3ClientVersion().sendAsync().get();
            if (!clientVersion.hasError()) {
                toastAsync("Connected!");
                connectButton.setText(getString(R.string.connectedToEthereum));
                connectButton.setBackgroundColor(0xFF7CCC26);
                connection = true;
            } else {
                toastAsync(clientVersion.getError().getMessage());
            }
        } catch (Exception e) {
            toastAsync(e.getMessage());
        }
    }

    /**
     * Show a dialog to create a wallet
     *
     * @param view: the view
     */
    public void createWalletDialog(View view) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View passwordView = getLayoutInflater().inflate(R.layout.set_password, null);
        EditText passwordET = passwordView.findViewById(R.id.passwordET);
        Button insertPassword = passwordView.findViewById(R.id.insertPasswordBtn);
        builder.setView(passwordView);
        final AlertDialog dialog = builder.show();

        insertPassword.setOnClickListener(view1 -> {
            hideKeyboard();
            password = passwordET.getText().toString();
            if (TextUtils.isEmpty(password) || password.length() < 4) {
                toastAsync("Your password must have 4 or more characters");
                return;
            }
            createWallet(password);
            dialog.dismiss();
        });
    }

    /**
     * Create a wallet and stores it on the device
     *
     * @param password: the entered password
     */
    public void createWallet(String password) {
        try {
            String wallet = WalletUtils.generateBip39Wallet(password, walletDirectory).toString();
            sharedPreferences.edit().putString(WALLET_DIRECTORY_KEY, walletDirectory.getAbsolutePath()).apply();
            refreshList();
            toastAsync("Wallet created!");
            String mnemonic = wallet.substring(wallet.indexOf("mnemonic='") + 10, wallet.indexOf("'}"));
            mnemonicPassphraseDialog(mnemonic);
        } catch (Exception e) {
            toastAsync("ERROR:" + e.getMessage());
        }
    }

    /**
     * Show a dialog with the mnemonic passphrase
     *
     * @param mnemonicPassphrase: the mnemonic passphrase used to recover a wallet
     */
    private void mnemonicPassphraseDialog(String mnemonicPassphrase) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View mnemonicView = getLayoutInflater().inflate(R.layout.passphrase, null);
        EditText word1 = mnemonicView.findViewById(R.id.word1);
        EditText word2 = mnemonicView.findViewById(R.id.word2);
        EditText word3 = mnemonicView.findViewById(R.id.word3);
        EditText word4 = mnemonicView.findViewById(R.id.word4);
        EditText word5 = mnemonicView.findViewById(R.id.word5);
        EditText word6 = mnemonicView.findViewById(R.id.word6);
        EditText word7 = mnemonicView.findViewById(R.id.word7);
        EditText word8 = mnemonicView.findViewById(R.id.word8);
        EditText word9 = mnemonicView.findViewById(R.id.word9);
        EditText word10 = mnemonicView.findViewById(R.id.word10);
        EditText word11 = mnemonicView.findViewById(R.id.word11);
        EditText word12 = mnemonicView.findViewById(R.id.word12);
        EditText[] array = {word1, word2, word3, word4, word5, word6, word7, word8, word9, word10, word11, word12};
        Button okButton = mnemonicView.findViewById(R.id.nextBtn);
        Button copyButton = mnemonicView.findViewById(R.id.copyPassphraseBtn);
        String[] arr = mnemonicPassphrase.split(" ");

        for (int i = 0; i < 12; i++) {
            array[i].setText(arr[i]);
            array[i].setKeyListener(null);
        }
        builder.setView(mnemonicView);
        final AlertDialog dialog = builder.show();
        dialog.setCanceledOnTouchOutside(false);

        okButton.setOnClickListener(view1 -> {
            dialog.dismiss();
            confirmPassphraseDialog(mnemonicPassphrase);
        });

        copyButton.setOnClickListener(view1 -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("", mnemonicPassphrase);
            clipboard.setPrimaryClip(clip);
            toastAsync("Mnemonic passphrase copied to clipboard.");
        });
    }

    /**
     * Show a dialog to confirm the user knowledge of the passphrase
     *
     * @param mnemonicPassphrase: the passphrase to check
     */
    private void confirmPassphraseDialog(String mnemonicPassphrase) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View mnemonicView = getLayoutInflater().inflate(R.layout.passphrase, null);
        EditText word1 = mnemonicView.findViewById(R.id.word1);
        EditText word2 = mnemonicView.findViewById(R.id.word2);
        EditText word3 = mnemonicView.findViewById(R.id.word3);
        EditText word4 = mnemonicView.findViewById(R.id.word4);
        EditText word5 = mnemonicView.findViewById(R.id.word5);
        EditText word6 = mnemonicView.findViewById(R.id.word6);
        EditText word7 = mnemonicView.findViewById(R.id.word7);
        EditText word8 = mnemonicView.findViewById(R.id.word8);
        EditText word9 = mnemonicView.findViewById(R.id.word9);
        EditText word10 = mnemonicView.findViewById(R.id.word10);
        EditText word11 = mnemonicView.findViewById(R.id.word11);
        EditText word12 = mnemonicView.findViewById(R.id.word12);

        EditText[] array = {word1, word2, word3, word4, word5, word6, word7, word8, word9, word10, word11, word12};
        Button okButton = mnemonicView.findViewById(R.id.nextBtn);
        Button copyButton = mnemonicView.findViewById(R.id.copyPassphraseBtn);
        okButton.setText(getString(R.string.confirm));
        copyButton.setVisibility(View.GONE);

        String[] arr = mnemonicPassphrase.split(" ");

        int[] excludedNumbers = new int[4];

        for (int i = 0; i < 4; i++) {
            excludedNumbers[i] = getRandomNumberInRange(0, 11);
        }

        boolean flag = true;

        for (int i = 0; i < 12; i++) {
            for (int j = 0; j < 4; j++) {
                if (excludedNumbers[j] == i)
                    flag = false;
            }
            if (flag) {
                array[i].setText(arr[i]);
                array[i].setEnabled(false);
            }
            flag = true;
        }

        builder.setView(mnemonicView);
        final AlertDialog dialog = builder.show();
        dialog.setCanceledOnTouchOutside(false);
        okButton.setOnClickListener(view1 -> {

            String mnemonic = word1.getText().toString() + " " + word2.getText().toString() + " " + word3.getText().toString() + " " +
                    word4.getText().toString() + " " + word5.getText().toString() + " " + word6.getText().toString() + " " +
                    word7.getText().toString() + " " + word8.getText().toString() + " " + word9.getText().toString() + " " +
                    word10.getText().toString() + " " + word11.getText().toString() + " " + word12.getText().toString();

            if (mnemonic.equals(mnemonicPassphrase)) {
                dialog.dismiss();
            } else {
                toastAsync("Wrong passphrase. Please retry");
            }
        });
    }

    /**
     * Unlock the wallet loading its credentials
     *
     * @param wallet: the wallet ot unlock
     */
    private void unlockWalletDialog(String wallet) {
        /*
        It's safe to assume that a Credentials object is not null only when it's correctly initialized.
        This occurs when the set_password used is correct. Therefore a non null Credentials is successfully unlocked.
         */
        if (accounts.get(wallet) != null) {
            toastAsync("Already unlocked");
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View passwordView = getLayoutInflater().inflate(R.layout.unlock_wallet, null);
        EditText passwordET = passwordView.findViewById(R.id.passwordET);
        Button insertPassword = passwordView.findViewById(R.id.insertPasswordBtn);

        builder.setView(passwordView);
        final AlertDialog dialog = builder.show();

        //If the loadCredentials return a non null object we can assume the input set_password is correct.
        insertPassword.setOnClickListener(view1 -> {
            password = passwordET.getText().toString();
            credentials = null;
            try {
                credentials = WalletUtils.loadCredentials(password, sharedPreferences.getString(WALLET_DIRECTORY_KEY, "") +
                        "/" + wallet);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (CipherException e) {
                e.printStackTrace();
            }
            if (credentials != null) {
                toastAsync("Unlocked!");
                accounts.put(wallet, credentials);
                dialog.dismiss();
            } else {
                toastAsync("Wrong password!");
                dialog.dismiss();
            }

        });
    }

    /**
     * Recover an HD wallet from a 12-words mnemonic phrase
     *
     */
    private void mnemonicRecovery() {
        //Here we set all the editText to receive the user passphrase
        //Not the most elegant solution but it gets the job done
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View recoveryView = getLayoutInflater().inflate(R.layout.recovery, null);
        EditText word1 = recoveryView.findViewById(R.id.word1);
        EditText word2 = recoveryView.findViewById(R.id.word2);
        EditText word3 = recoveryView.findViewById(R.id.word3);
        EditText word4 = recoveryView.findViewById(R.id.word4);
        EditText word5 = recoveryView.findViewById(R.id.word5);
        EditText word6 = recoveryView.findViewById(R.id.word6);
        EditText word7 = recoveryView.findViewById(R.id.word7);
        EditText word8 = recoveryView.findViewById(R.id.word8);
        EditText word9 = recoveryView.findViewById(R.id.word9);
        EditText word10 = recoveryView.findViewById(R.id.word10);
        EditText word11 = recoveryView.findViewById(R.id.word11);
        EditText word12 = recoveryView.findViewById(R.id.word12);
        EditText[] array = {word1, word2, word3, word4, word5, word6, word7, word8, word9, word10, word11, word12};
        Button pasteButton = recoveryView.findViewById(R.id.pasteBtn);
        Button recoveryButton = recoveryView.findViewById(R.id.recoveryBtn);

        //Here we can paste automatically the passphrase in the form
        pasteButton.setOnClickListener(view1 -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            String mnemonic = clipboard.getText().toString();
            String[] arr = mnemonic.split(" ");
            for (int i = 0; i < 12; i++) {
                array[i].setText(arr[i]);
            }
        });

        //Here we recover the wallet with the passphrase
        recoveryButton.setOnClickListener(view1 -> {
            String mnemonic = word1.getText().toString() + " " + word2.getText().toString() + " " + word3.getText().toString() + " " +
                    word4.getText().toString() + " " + word5.getText().toString() + " " + word6.getText().toString() + " " +
                    word7.getText().toString() + " " + word8.getText().toString() + " " + word9.getText().toString() + " " +
                    word10.getText().toString() + " " + word11.getText().toString() + " " + word12.getText().toString();
            byte[] seed = MnemonicUtils.generateSeed(mnemonic, password);
            ECKeyPair privateKey = ECKeyPair.create(sha256(seed));
            System.out.println(privateKey);
            try {
                WalletUtils.generateWalletFile(password, privateKey, walletDirectory, false);
                toastAsync("Wallet recovered!");
            } catch (CipherException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            refreshList();
        });

        builder.setView(recoveryView);
        builder.show();
    }

    /**
     * Send ether from a wallet to another
     *
     * @param wallet:  the wallet of the sender
     * @param address: the address of the receiver
     * @param value:   the amount of ether sent
     */
    public void sendTransaction(String wallet, String address, String value) {
        activateBar();
        new Thread(() -> {
            try {
                /*
                accounts.get(wallet) cannot possibly be null, because the wallet must be unlocked.
                An unlocked wallet is inevitably not null.
                */
                toastAsync("Transaction started!");
                TransactionReceipt receipt = Transfer.sendFunds(web3j, accounts.get(wallet), address,
                        new BigDecimal(value), Convert.Unit.ETHER).sendAsync().get();
                toastAsync("Transaction complete: " + receipt.getTransactionHash());
            } catch (Exception e) {
                toastAsync(e.getMessage());
            }
        }).start();
        deactivateBar();
    }

    /**
     * Call the SignUpRegistry contract functions
     *
     * @param wallet: the wallet calling the contract functions
     */
    public void smartContractCall(String wallet) {
        if (!checkUnlocked(wallet)) {
            return;
        }
        if (connection) {
            credentials = accounts.get(wallet);
            SignUpRegistry signUpRegistry = SignUpRegistry.load(smartContractAddress, web3j, credentials, gasPrice, gasLimit);
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            View transactionView = getLayoutInflater().inflate(R.layout.contracts, null);
            Button signUpButton = transactionView.findViewById(R.id.registerBtn);
            Button uploadButton = transactionView.findViewById(R.id.uploadDocBtn);
            Button getDocumentsButton = transactionView.findViewById(R.id.docListBtn);

            /*
            This function registers the current user, aka the wallet public key, to the contract.
            A registered user can call the other smart contract functions.
             */
            signUpButton.setOnClickListener(view1 -> new Thread(() -> {
                hideKeyboard();
                activateBar();
                TransactionReceipt transactionReceipt = null;
                boolean b = false;
                try {
                    b = signUpRegistry.isExist(credentials.getAddress()).sendAsync().get(2, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                } catch (TimeoutException e) {
                    e.printStackTrace();
                }
                if (b) {
                    toastAsync("You are already subscribed to this service");
                    deactivateBar();
                } else {
                    try {
                        transactionReceipt = signUpRegistry.addUser(credentials.getAddress(), false).sendAsync().get(2, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (transactionReceipt != null) {
                        toastAsync("Successful transaction: gas used " + transactionReceipt.getGasUsed());
                        System.out.println("Transaction hash: " + transactionReceipt.getTransactionHash());
                        System.out.println("Successful transaction: gas used " + transactionReceipt.getGasUsed());
                    }
                    deactivateBar();
                }
            }).start());

            /*
            This function uploads the IPFS hash of a document on the chain.
            The hash is stored in the input data of the transaction.
            */
            uploadButton.setOnClickListener(view1 -> new Thread(() -> {
                hideKeyboard();
                activateBar();

                Intent chooseFile;
                Intent intent;
                chooseFile = new Intent(Intent.ACTION_GET_CONTENT);
                chooseFile.addCategory(Intent.CATEGORY_OPENABLE);
                chooseFile.setType("text/plain");
                intent = Intent.createChooser(chooseFile, "Choose a file");
                startActivityForResult(intent, 1000);
                synchronized (sharedLock) {
                    try {
                        sharedLock.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                MessageDigest md = null;
                try {
                    md = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException e) {
                    e.printStackTrace();
                }

                // Change this to UTF-16 if needed
                md.update(fileToHash.toString().getBytes(StandardCharsets.UTF_8));
                byte[] digest = md.digest();

                String hex = "Qm" + String.format("%064x", new BigInteger(1, digest));

                boolean b = false;
                try {
                    b = signUpRegistry.existHash(credentials.getAddress(), hex).sendAsync().get(2, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                } catch (TimeoutException e) {
                    e.printStackTrace();
                }
                if (b) {
                    toastAsync("You already uploaded that document");
                    deactivateBar();
                } else {
                    TransactionReceipt transactionReceipt = null;
                    try {
                        transactionReceipt = signUpRegistry.addDocument(credentials.getAddress(),
                                hex, "my_eSK").sendAsync().get(3, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (transactionReceipt != null) {
                        toastAsync("Successful transaction: gas used " + transactionReceipt.getGasUsed());
                        System.out.println("Successful transaction: gas used " + transactionReceipt.getGasUsed());
                        System.out.println("Transaction hash: " + transactionReceipt.getTransactionHash());
                    } else {
                        toastAsync("Something went wrong. Maybe not enough gas?");
                    }
                    deactivateBar();
                }
            }).start());
            builder.setView(transactionView);
            builder.show();

            /*
            2019/07/13
            The Web3j API cannot receive any data structure from a smart contract.
            As a matter of fact, it's not possible obtain a list of uploaded documents.
            However, Web3j is still in beta and this problem may be solved in the future.

            UPDATE
            2019/07/22
            This function now returns a string that is a concatenation of hashes.
            That's not the optimal solution, but the best we got for now.
            The string is received and simply displayed by the app.
            */
            getDocumentsButton.setOnClickListener(view1 -> {
                try {
                    String documents = signUpRegistry.getHashList(credentials.getAddress()).sendAsync().get(3, TimeUnit.MINUTES);
                    AlertDialog alertDialog = new AlertDialog.Builder(this)
                            .setTitle("Uploaded documents")
                            .setMessage(documents)
                            .setNegativeButton("Close", (dialog, which) -> dialog.dismiss())
                            .create();
                    alertDialog.show();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

        } else offChainErrorDialog();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ACTIVITY_CHOOSE_FILE) {
            Uri uri = data.getData();
            String path = uri.getPath();
            fileToHash = new File(path);
            Log.d("File acquired:", fileToHash.toString());
            synchronized (sharedLock) {
                sharedLock.notify();
            }
        }
    }

    /**
     * Delete a given wallet
     *
     * @param wallet: the wallet to delete
     */
    public void deleteWallet(final String wallet) {
        AlertDialog alertDialog = new AlertDialog.Builder(this)
                .setTitle("Delete Wallet")
                .setMessage("Do you want to delete this wallet from the storage?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    File file = new File(sharedPreferences.getString(WALLET_DIRECTORY_KEY, "") +
                            "/" + wallet);
                    if (file.exists() && file.delete()) {
                        toastAsync("Wallet deleted");
                    } else {
                        System.err.println(
                                "I cannot find '" + file + "' ('" + file.getAbsolutePath() + "')");
                    }
                    walletList.remove(wallet);
                    listAdapter.notifyDataSetChanged();
                })
                .setNegativeButton("No", (dialog, which) -> dialog.dismiss())
                .create();
        alertDialog.show();
    }

    /**
     * Display address and eth balance of a given wallet.
     *
     * @param wallet: the wallet to display info
     */
    public void infoDialog(final String wallet) {
        if (!checkUnlocked(wallet)) {
            return;
        }
        if (connection) {
            Credentials credentials = accounts.get(wallet);
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            View transactionView = getLayoutInflater().inflate(R.layout.info, null);
            EditText showAddress = transactionView.findViewById(R.id.AddressET);
            showAddress.setText(credentials.getAddress());

            EditText showBalance = transactionView.findViewById(R.id.ethBalanceET);
            showBalance.setText(getBalance(credentials.getAddress()));
            Button copyButton = transactionView.findViewById(R.id.copyPassphraseBtn);
            copyButton.setOnClickListener(view1 -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("", showAddress.getText());
                clipboard.setPrimaryClip(clip);
                toastAsync("Address has been copied to clipboard.");
            });
            builder.setView(transactionView);
            builder.show();
        } else offChainErrorDialog();
    }

    /**
     * Check if a given wallet is unlocked
     *
     * @param wallet: the wallet to check
     * @return true if unlocked, false otherwise
     */
    public boolean checkUnlocked(String wallet) {
        if (accounts.get(wallet) != null) {
            return true;
        } else {
            AlertDialog alertDialog = new AlertDialog.Builder(this)
                    .setTitle("Error")
                    .setMessage("Unlock the wallet")
                    .create();
            alertDialog.show();
            return false;
        }
    }

    /**
     * Show a dialog to acquire transaction info.
     *
     * @param wallet: the sender of the transaction
     */
    public void transactionDialog(String wallet) {
        if (!checkUnlocked(wallet)) {
            return;
        }
        if (connection) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            View transactionView = getLayoutInflater().inflate(R.layout.transaction, null);
            EditText insertAddress = transactionView.findViewById(R.id.AddressET);
            EditText insertValue = transactionView.findViewById(R.id.ethBalanceET);
            Button pasteButton = transactionView.findViewById(R.id.pasteBtn);
            Button confirmButton = transactionView.findViewById(R.id.confirmBtn);

            builder.setView(transactionView);
            final AlertDialog dialog = builder.show();

            pasteButton.setOnClickListener(view1 -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                insertAddress.setText(clipboard.getText());
            });
            confirmButton.setOnClickListener(view1 -> {
                hideKeyboard();
                sendTransaction(wallet, insertAddress.getText().toString(), insertValue.getText().toString());
                dialog.dismiss();
            });
        } else offChainErrorDialog();
    }

    /**
     * Check the state of the connection to the blockchain
     *
     */
    public void offChainErrorDialog() {
        AlertDialog alertDialog = new AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage("Connect to the blockchain first")
                .create();
        alertDialog.show();
    }

    /**
     * Compute the ether balance of a given address
     *
     * @param address: the given address
     * @return the ether balance
     */
    public String getBalance(String address) {
        // send asynchronous requests to get balance
        EthGetBalance ethGetBalance = null;
        try {
            ethGetBalance = web3j
                    .ethGetBalance(address, DefaultBlockParameterName.LATEST)
                    .sendAsync()
                    .get();
        } catch (Exception e) {
            toastAsync(e.getMessage());
        }
        BigDecimal etherValue = Convert.fromWei(ethGetBalance.getBalance().toString(), Convert.Unit.ETHER);
        return etherValue.toString();
    }

    /**
     * List all the wallets on the device
     *
     */
    public void refreshList() {
        File folder = new File(sharedPreferences.getString(WALLET_DIRECTORY_KEY, ""));
        if (folder.exists()) {
            for (File f : folder.listFiles()) {
                if (!walletList.contains(f.getName())) {
                    walletList.add(f.getName());
                }
            }
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        }
    }

    /**
     * Display messages and errors, mainly used for testing purposes
     *
     */
    public void toastAsync(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    /**
     * Show the progress bar
     *
     */
    public void activateBar() {
        runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE));
    }

    /**
     * Hide the progress bar
     *
     */
    public void deactivateBar() {
        runOnUiThread(() -> progressBar.setVisibility(View.INVISIBLE));
    }

    /**
     * Setup Security provider that corrects some issues with the BouncyCastle library
     *
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
     * Hide the digital keyboard
     *
     */
    private void hideKeyboard() {
        View view = this.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /**
     * Inner class to display wallets on the device
     *
     */
    public class WalletAdapter extends ArrayAdapter<String> {

        WalletAdapter(Context context, int textView) {
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

            final TextView walletTV = itemView.findViewById(R.id.walletAliasTV);
            walletTV.setText(walletList.get(position));

            final Button lock = itemView.findViewById(R.id.unlockBtn);
            lock.setOnClickListener(view -> unlockWalletDialog(walletTV.getText().toString()));

            final Button address = itemView.findViewById(R.id.infoBtn);
            address.setOnClickListener(view -> infoDialog(walletTV.getText().toString()));

            final Button transaction = itemView.findViewById(R.id.transactionBtn);
            transaction.setOnClickListener(view -> transactionDialog(walletTV.getText().toString()));

            final Button uploadDocument = itemView.findViewById(R.id.uploadDocumentBtn);
            uploadDocument.setOnClickListener(view -> {
                try {
                    smartContractCall(walletTV.getText().toString());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            final Button deleteButton = itemView.findViewById(R.id.deleteWalletBtn);
            deleteButton.setOnClickListener(view -> deleteWallet(walletTV.getText().toString()));

            return itemView;
        }

        @Override
        public String getItem(int position) {
            return walletList.get(position);
        }
    }

}