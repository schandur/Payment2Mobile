package org.rhok.payout2mobile;

import java.io.IOException;
import java.text.SimpleDateFormat;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.rhok.payout2mobile.ProtocolProviders.ProtocolProvider;
import org.rhok.payout2mobile.ProtocolProviders.SMSProtocolProvider;
import org.rhok.payout2mobile.controllers.CC;
import org.rhok.payout2mobile.controllers.PolicyController;
import org.rhok.payout2mobile.model.Identity;
import org.rhok.payout2mobile.model.IdentityType;
import org.rhok.payout2mobile.model.Location;
import org.rhok.payout2mobile.model.Policy;
import org.rhok.payout2mobile.model.PolicyDetails;
import org.rhok.payout2mobile.model.ProductQuantity;
import org.rhok.payout2mobile.model.Quote;

public class SmsCallback extends HttpServlet {

	private SMSProtocolProvider smsprovider = new SMSProtocolProvider();

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		String phoneFrom = req.getParameter("u");
		String message = req.getParameter("q");

		getServletContext().setAttribute("output", resp);

		parseMessage(phoneFrom, message);
	}

	protected PolicyController policyController() {
		return CC.get().policy();
	}

	public void parseMessage(String phoneFrom, String message) {
		try {
			String tokens[] = message.split(",");

			if (tokens[0].equals("quote")) {
				quote(phoneFrom, tokens);
			} else if (tokens[0].equals("purchase")) {
				purchase(phoneFrom, tokens);
			}
		} catch (IOException ioe) {
			// TODO: log, etc.
		}
	}

	// quote, <Customer.Phone>, <Location>, <Qty> <Product> [, <Qty>
	// <Product>...]
	public void quote(String phoneVendor, String[] tokens) throws IOException {
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
			sendResponse(phoneVendor,
					smsprovider.quoteResponse(phoneCustomer, bestQuote));
		} else {
			sendResponse(phoneVendor, "No insurance available.");
		}
	}

	private void sendResponse(String phone, String response) throws IOException {
		HttpServletResponse resp = (HttpServletResponse) getServletContext()
				.getAttribute("output");

		resp.getOutputStream().println(response);
	}

	// purchase
	public void purchase(String phoneVendor, String[] tokens)
			throws IOException {

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
				sendResponse(phoneCustomer, purchaseResponse(policy));
				return;
			}
		}
		sendResponse(phoneVendor, "Policy Not Purchased");
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

}
