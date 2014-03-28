package ca.uhn.fhir.rest.method;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;

import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.model.api.Bundle;
import ca.uhn.fhir.model.api.BundleEntry;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.api.annotation.ResourceDef;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.client.exceptions.InvalidResponseException;
import ca.uhn.fhir.rest.server.Constants;
import ca.uhn.fhir.rest.server.EncodingUtil;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.RestfulServer.NarrativeModeEnum;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

public abstract class BaseResourceReturningMethodBinding extends BaseMethodBinding {

	protected static final Set<String> ALLOWED_PARAMS;
	private MethodReturnTypeEnum myMethodReturnType;
	private String myResourceName;

	public BaseResourceReturningMethodBinding(Class<? extends IResource> theReturnResourceType, Method theMethod, FhirContext theConetxt) {
		super(theMethod, theConetxt);

		Class<?> methodReturnType = theMethod.getReturnType();
		if (Collection.class.isAssignableFrom(methodReturnType)) {
			myMethodReturnType = MethodReturnTypeEnum.LIST_OF_RESOURCES;
		} else if (IResource.class.isAssignableFrom(methodReturnType)) {
			myMethodReturnType = MethodReturnTypeEnum.RESOURCE;
		} else if (Bundle.class.isAssignableFrom(methodReturnType)) {
			myMethodReturnType = MethodReturnTypeEnum.BUNDLE;
		} else {
			throw new ConfigurationException("Invalid return type '" + methodReturnType.getCanonicalName() + "' on method '" + theMethod.getName() + "' on type: " + theMethod.getDeclaringClass().getCanonicalName());
		}

		ResourceDef resourceDefAnnotation = theReturnResourceType.getAnnotation(ResourceDef.class);
		if (resourceDefAnnotation == null) {
			throw new ConfigurationException(theReturnResourceType.getCanonicalName() + " has no @" + ResourceDef.class.getSimpleName() + " annotation");
		}
		myResourceName = resourceDefAnnotation.name();
	}

	public MethodReturnTypeEnum getMethodReturnType() {
		return myMethodReturnType;
	}

	public String getResourceName() {
		return myResourceName;
	}

	public abstract ReturnTypeEnum getReturnType();

	@Override
	public Object invokeClient(String theResponseMimeType, Reader theResponseReader, int theResponseStatusCode,Map<String, List<String>> theHeaders) throws IOException {
		IParser parser = createAppropriateParser(theResponseMimeType, theResponseReader, theResponseStatusCode);

		switch (getReturnType()) {
		case BUNDLE: {
			Bundle bundle = parser.parseBundle(theResponseReader);
			switch (getMethodReturnType()) {
			case BUNDLE:
				return bundle;
			case LIST_OF_RESOURCES:
				return bundle.toListOfResources();
			case RESOURCE:
				List<IResource> list = bundle.toListOfResources();
				if (list.size() == 0) {
					return null;
				} else if (list.size() == 1) {
					return list.get(0);
				} else {
					throw new InvalidResponseException("FHIR server call returned a bundle with multiple resources, but this method is only able to returns one.");
				}
			}
			break;
		}
		case RESOURCE: {
			IResource resource = parser.parseResource(theResponseReader);
			switch (getMethodReturnType()) {
			case BUNDLE:
				return Bundle.withSingleResource(resource);
			case LIST_OF_RESOURCES:
				return Collections.singletonList(resource);
			case RESOURCE:
				return resource;
			}
			break;
		}
		}

		throw new IllegalStateException("Should not get here!");
	}


	public abstract List<IResource> invokeServer(Object theResourceProvider, IdDt theId, IdDt theVersionId, Map<String, String[]> theParameterValues) throws InvalidRequestException, InternalErrorException;

	@Override
	public void invokeServer(RestfulServer theServer, Request theRequest, HttpServletResponse theResponse) throws BaseServerResponseException, IOException {

		// Pretty print
		Map<String, String[]> params = theRequest.getParameters();
		String[] pretty = params.remove(Constants.PARAM_PRETTY);
		boolean prettyPrint = false;
		if (pretty != null && pretty.length > 0) {
			if ("true".equals(pretty[0])) {
				prettyPrint = true;
			}
		}

		// Narrative mode
		String[] narrative = params.remove(Constants.PARAM_NARRATIVE);
		NarrativeModeEnum narrativeMode = null;
		if (narrative != null && narrative.length > 0) {
			narrativeMode = NarrativeModeEnum.valueOfCaseInsensitive(narrative[0]);
		}
		if (narrativeMode == null) {
			narrativeMode = NarrativeModeEnum.NORMAL;
		}

		// Determine response encoding
		EncodingUtil responseEncoding = determineResponseEncoding(theRequest.getServletRequest(), params);

		// Is this request coming from a browser
		String uaHeader = theRequest.getServletRequest().getHeader("user-agent");
		boolean requestIsBrowser = false;
		if (uaHeader != null && uaHeader.contains("Mozilla")) {
			requestIsBrowser = true;
		}

		List<IResource> result = invokeServer(theRequest.getResourceProvider(), theRequest.getId(), theRequest.getVersion(), theRequest.getParameters());
		switch (getReturnType()) {
		case BUNDLE:
			streamResponseAsBundle(theServer, theResponse, result, responseEncoding, theRequest.getFhirServerBase(), theRequest.getCompleteUrl(), prettyPrint, requestIsBrowser, narrativeMode);
			break;
		case RESOURCE:
			if (result.size() == 0) {
				throw new ResourceNotFoundException(theRequest.getId());
			} else if (result.size() > 1) {
				throw new InternalErrorException("Method returned multiple resources");
			}
			streamResponseAsResource(theServer, theResponse, result.get(0), responseEncoding, prettyPrint, requestIsBrowser, narrativeMode);
			break;
		}
	}


	static {
		HashSet<String> set = new HashSet<String>();
		set.add(Constants.PARAM_FORMAT);
		set.add(Constants.PARAM_NARRATIVE);
		set.add(Constants.PARAM_PRETTY);
		ALLOWED_PARAMS = Collections.unmodifiableSet(set);
	}
	
	private IParser getNewParser(EncodingUtil theResponseEncoding, boolean thePrettyPrint, NarrativeModeEnum theNarrativeMode) {
		IParser parser;
		switch (theResponseEncoding) {
		case JSON:
			parser = getContext().newJsonParser();
			break;
		case XML:
		default:
			parser = getContext().newXmlParser();
			break;
		}
		return parser.setPrettyPrint(thePrettyPrint).setSuppressNarratives(theNarrativeMode == NarrativeModeEnum.SUPPRESS);
	}

	private void streamResponseAsBundle(RestfulServer theServer, HttpServletResponse theHttpResponse, List<IResource> theResult, EncodingUtil theResponseEncoding, String theServerBase, String theCompleteUrl, boolean thePrettyPrint, boolean theRequestIsBrowser,
			NarrativeModeEnum theNarrativeMode) throws IOException {
		assert !theServerBase.endsWith("/");

		theHttpResponse.setStatus(200);

		if (theRequestIsBrowser && theServer.isUseBrowserFriendlyContentTypes()) {
			theHttpResponse.setContentType(theResponseEncoding.getBrowserFriendlyBundleContentType());
		} else if (theNarrativeMode == NarrativeModeEnum.ONLY) {
			theHttpResponse.setContentType(Constants.CT_HTML);
		} else {
			theHttpResponse.setContentType(theResponseEncoding.getBundleContentType());
		}

		theHttpResponse.setCharacterEncoding("UTF-8");

		theServer.addHapiHeader(theHttpResponse);
		
		Bundle bundle = new Bundle();
		bundle.getAuthorName().setValue(getClass().getCanonicalName());
		bundle.getBundleId().setValue(UUID.randomUUID().toString());
		bundle.getPublished().setToCurrentTimeInLocalTimeZone();
		bundle.getLinkBase().setValue(theServerBase);
		bundle.getLinkSelf().setValue(theCompleteUrl);

		for (IResource next : theResult) {
			BundleEntry entry = new BundleEntry();
			bundle.getEntries().add(entry);

			entry.setResource(next);

			RuntimeResourceDefinition def = getContext().getResourceDefinition(next);

			if (next.getId() != null && StringUtils.isNotBlank(next.getId().getValue())) {
				entry.getEntryId().setValue(next.getId().getValue());
				entry.getTitle().setValue(def.getName() + " " + next.getId().getValue());

				StringBuilder b = new StringBuilder();
				b.append(theServerBase);
				b.append('/');
				b.append(def.getName());
				b.append('/');
				b.append(next.getId().getValue());
				boolean haveQ = false;
				if (thePrettyPrint) {
					b.append('?').append(Constants.PARAM_PRETTY).append("=true");
					haveQ = true;
				}
				if (theResponseEncoding == EncodingUtil.JSON) {
					if (!haveQ) {
						b.append('?');
						haveQ = true;
					} else {
						b.append('&');
					}
					b.append(Constants.PARAM_FORMAT).append("=json");
				}
				if (theNarrativeMode != NarrativeModeEnum.NORMAL) {
					b.append(Constants.PARAM_NARRATIVE).append("=").append(theNarrativeMode.name().toLowerCase());
				}
				entry.getLinkSelf().setValue(b.toString());
			}
		}

		bundle.getTotalResults().setValue(theResult.size());

		PrintWriter writer = theHttpResponse.getWriter();
		try {
			if (theNarrativeMode == NarrativeModeEnum.ONLY) {
				for (IResource next : theResult) {
					writer.append(next.getText().getDiv().getValueAsString());
					writer.append("<hr/>");
				}
			} else {
				getNewParser(theResponseEncoding, thePrettyPrint, theNarrativeMode).encodeBundleToWriter(bundle, writer);
			}
		} finally {
			writer.close();
		}
	}

	
	private void streamResponseAsResource(RestfulServer theServer, HttpServletResponse theHttpResponse, IResource theResource, EncodingUtil theResponseEncoding, boolean thePrettyPrint, boolean theRequestIsBrowser, NarrativeModeEnum theNarrativeMode) throws IOException {

		theHttpResponse.setStatus(200);
		if (theRequestIsBrowser && theServer.isUseBrowserFriendlyContentTypes()) {
			theHttpResponse.setContentType(theResponseEncoding.getBrowserFriendlyBundleContentType());
		} else if (theNarrativeMode == NarrativeModeEnum.ONLY) {
			theHttpResponse.setContentType(Constants.CT_HTML);
		} else {
			theHttpResponse.setContentType(theResponseEncoding.getResourceContentType());
		}
		theHttpResponse.setCharacterEncoding("UTF-8");

		PrintWriter writer = theHttpResponse.getWriter();
		try {
			if (theNarrativeMode == NarrativeModeEnum.ONLY) {
				writer.append(theResource.getText().getDiv().getValueAsString());
			} else {
				getNewParser(theResponseEncoding, thePrettyPrint, theNarrativeMode).encodeResourceToWriter(theResource, writer);
			}
		} finally {
			writer.close();
		}

	}

	public enum MethodReturnTypeEnum {
		BUNDLE, LIST_OF_RESOURCES, RESOURCE
	}

	public enum ReturnTypeEnum {
		BUNDLE, RESOURCE
	}

}
