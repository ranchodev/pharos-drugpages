package ix.ginas.models.v1;

import java.lang.reflect.*;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;

import ix.core.models.Keyword;
import ix.core.models.Structure;
import ix.core.models.Value;
import ix.core.models.Keyword;
import ix.ginas.models.Ginas;

public class StructureSerializer extends JsonSerializer<Structure> {
    public StructureSerializer () {}
    public void serializeValue (Structure struc, JsonGenerator jgen,
                                SerializerProvider provider)
        throws IOException, JsonProcessingException {
        if (struc == null) {
            provider.defaultSerializeNull(jgen);
            return;
        }
        
        provider.defaultSerializeField("created", struc.created, jgen);
        provider.defaultSerializeField("modified", struc.modified, jgen);
        provider.defaultSerializeField("deprecated", struc.deprecated, jgen);
        provider.defaultSerializeField("digest", struc.digest, jgen);
        provider.defaultSerializeField("molfile", struc.molfile, jgen);
        provider.defaultSerializeField("smiles", struc.smiles, jgen);
        provider.defaultSerializeField("formula", struc.formula, jgen);
        provider.defaultSerializeField
            ("stereochemistry", struc.stereoChemistry, jgen);
        provider.defaultSerializeField
            ("opticalActivity", struc.opticalActivity, jgen);
        provider.defaultSerializeField
            ("atropisomerism", struc.atropisomerism, jgen);
        provider.defaultSerializeField
            ("stereoComments", struc.stereoComments, jgen);
        provider.defaultSerializeField
            ("stereoCenters", struc.stereoCenters, jgen);
        provider.defaultSerializeField
            ("definedStereo", struc.definedStereo, jgen);
        provider.defaultSerializeField("ezCenters", struc.ezCenters, jgen);
        provider.defaultSerializeField("charge", struc.charge, jgen);
        provider.defaultSerializeField("mwt", struc.mwt, jgen);
        List<String> refs = new ArrayList<String>();
        for (Value val : struc.properties) {
            if (Structure.H_LyChI_L4.equals(val.label)) {
                Keyword kw = (Keyword)val;
                provider.defaultSerializeField("hash", kw.term, jgen);
            }
            else if (Ginas.REFERENCE.equals(val.label)) {
                Keyword kw = (Keyword)val;
                refs.add(kw.term);
            }
        }
        provider.defaultSerializeField("references", refs, jgen);
    }
    
    public void serialize (Structure struc, JsonGenerator jgen,
                           SerializerProvider provider)
        throws IOException, JsonProcessingException {
        jgen.writeStartObject();
        serializeValue (struc, jgen, provider);
        jgen.writeEndObject();
    }
}