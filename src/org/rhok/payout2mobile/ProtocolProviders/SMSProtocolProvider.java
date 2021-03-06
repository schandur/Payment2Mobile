package org.rhok.payout2mobile.ProtocolProviders;

import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import org.json.JSONObject;
import org.rhok.payout2mobile.controllers.CC;
import org.rhok.payout2mobile.controllers.PolicyController;
import org.rhok.payout2mobile.model.*;

import com.textmarks.api2client.TextMarksV2APIClient;
import com.textmarks.api2client.TextMarksV2APIClientException;

public class SMSProtocolProvider implements ProtocolProvider {

	private TextMarksV2APIClient tmapi;

	private static String TEXTMARKS_API_KEY = "";
	private static String TEXTMARKS_API_USER = "";
	private static String TEXTMARKS_API_PASS = "";
	private static String TEXTMARKS_KEYWORD = "INSMOPAY";
	Map<String, Object> textmarksParams;

	public SMSProtocolProvider() {
		// This is the constructor, here we need to setup handling SMS messages
		// when you receive a message, call "parseMessage"
		tmapi = new TextMarksV2APIClient();
		tmapi.setApiKey(TEXTMARKS_API_KEY);
		tmapi.setAuthUser(TEXTMARKS_API_USER);
		tmapi.setAuthPass(TEXTMARKS_API_PASS);

		textmarksParams = new HashMap<String, Object>();
		textmarksParams.put("tm", TEXTMARKS_KEYWORD);
	}

	public synchronized void sendMessage(String phoneTo, String message) {
		// needs to be implemented
		// this should send a text to the phone number specified
		// with the message specified
		try {
			textmarksParams.clear();
			textmarksParams.put("to", phoneTo);
			textmarksParams.put("str", message);

			JSONObject joResp = tmapi.call("GroupLeader", "send_one_message",
					textmarksParams);
		} catch (TextMarksV2APIClientException te) {
			// TODO: stuff
		}
	}

	// quote, <Customer.Phone>, <Location>, <Qty> <Product> [, <Qty>
	// <Product>...]
	public void quote(String phoneVendor, String[] tokens) {
		String phoneCustomer = tokens[1].trim();
		PolicyDetails details = new PolicyDetails(Location.parse(tokens[2]));

		for (int i = 3; i < tokens.length; i++) {
			String parts[] = tokens[i].trim().split(" ");
			details.getProducts().add(
					new ProductQuantity(Integer.parseInt(parts[0]), parts[1]));
		}

		// load the vendor
		Identity vendor = CC.get().identity().find(phoneVendor);

		// get the customer
		Identity customer = CC.get().identity().find(phoneCustomer);
		if (customer == null) {
			// create the customer
			customer = CC.get().identity()
					.create(vendor, phoneCustomer, "", IdentityType.Customer);
		}

		// now we need to pull a best quote
		Quote bestQuote = policyController().getBestQuote(vendor, customer,
				details);

		// send the quote to the vendor
		if (bestQuote != null) {
			sendMessage(phoneVendor, quoteResponse(phoneCustomer, bestQuote));
		} else {
			sendMessage(phoneVendor, "No insurance available.");
		}
	}

	// premium: <premium> customer: <phoneCustomer>
	public String quoteResponse(String phoneCustomer, Quote quote) {
		return String.format("premium: %.4f customer: %s", quote.getPremium(),
				phoneCustomer);
	}

	protected PolicyController policyController() {
		return CC.get().policy();
	}

	// purchase
	public void purchase(String phoneVendor, String[] tokens) {

		// We need to find the vendor's identity
		Identity vendor = CC.get().identity().find(phoneVendor);

		// We need to find the last quote and customer
		Quote lastQuote = policyController().getLastQuote(vendor);
		Identity lastCustomer = policyController().getLastCustomer(vendor);

		if ((lastQuote != null) && (lastCustomer != null)) {
			String phoneCustomer = lastCustomer.getPhoneNumber();

			// purchase the quote
			Policy policy = policyController().purchasePolicy(lastCustomer,
					lastQuote);

			if (policy != null) {
				sendMessage(phoneCustomer, purchaseResponse(policy));
				return;
			}
		}
		sendMessage(phoneVendor, "Policy Not Purchased");
	}

	// coverage <PolicyId> <Expiry> <Risk> <Qty><Product>[, <Qty><Product>]
	protected String purchaseResponse(Policy policy) {
		StringBuilder sb = new StringBuilder();
		SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy");

		sb.append(String.format("coverage #: %s exp: %s %s insured: ",
				policy.getPolicyId(), sdf.format(policy.getExpiry()),
				policy.getDescription()));

		// Demeter is going to kill me for this line, LOL.
		for (ProductQuantity product : policy.getQuote().getDetails()
				.getProducts()) {
			sb.append(String.format("%d %s", product.getQuantity(),
					product.getProduct()));
		}

		return sb.toString();
	}

	public void run() {
		// noop
	}

	public void confirmCoverage(Policy policy) {
		sendMessage(policy.getCustomer().getPhoneNumber(),
				purchaseResponse(policy));
	}

}
