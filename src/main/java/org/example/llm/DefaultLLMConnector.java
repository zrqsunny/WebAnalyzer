package org.example.llm;

import com.knuddels.jtokkit.api.EncodingType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.printer.CallGraphPrinter;
import org.example.rag.RepositoryBuilder;
import org.example.rag.es.ElasticsearchUtils;
import org.example.utils.DotProcessor;
import org.example.utils.DotReader;
import org.example.utils.MethodInvocationAnalyzer;
import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.ir.IR;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class DefaultLLMConnector {

    private final String apiKey = "sk-s0cwmAJ7OmaW4c0qgxNcLFkmbKmbglfwnp8ghUXKZRjMakjH";

    private final String apiUrl = "https://api.chatanywhere.tech/v1/chat/completions";

    private final GPTClient gptModel = new GPTClient(apiKey, apiUrl);

    private final CallGraph<Invoke, JMethod> callGraph;

    private final DotReader dotReader;

    private final Logger logger = LogManager.getLogger(DefaultLLMConnector.class);

    private final int MAX_SEND_TOKENS = 3900;

    // gpt3.5 / gpt-4 encodingType
    private static final EncodingType ENCODING_TYPE = EncodingType.CL100K_BASE;

    private final int BACKGROUND_SYSTEM_PROMPT_TOKENS = Tokenizer.countTokens(Prompt.BACKGROUND_SYSTEM_PROMPT.getContent(), ENCODING_TYPE);

    private final int USER_PROMPT_TOKENS = Tokenizer.countTokens(Prompt.USER_PROMPT.getContent(), ENCODING_TYPE);

    private final int MAX_DYNAMIC_TOKENS = MAX_SEND_TOKENS - BACKGROUND_SYSTEM_PROMPT_TOKENS - USER_PROMPT_TOKENS;

    private final HashMap<String, List<String>> invocationMap = new HashMap<>();


    public DefaultLLMConnector(CallGraph<Invoke, JMethod> callGraph) {
        this.callGraph = callGraph;
        this.dotReader = new DotReader("output/callFlows");
    }

    public void setInvocationMap(){
        callGraph.entryMethods().forEach(jMethod -> {
            String key = String.valueOf(jMethod.getDeclaringClass()) + '.' +
                    jMethod.getName() + '(' +
                    jMethod.getParamTypes()
                            .stream()
                            .map(Type::toString)
                            .collect(Collectors.joining(",")) +
                    ')';
            Set<JMethod> allCallees = MethodInvocationAnalyzer.getAllCallees(callGraph, jMethod);
            List<String> calleesList = new ArrayList<>();
            allCallees.forEach(callee -> {
                calleesList.add(callee.getSignature());
            });
            invocationMap.put(key, calleesList);
        });
    }

    public void analyze() throws Exception {
        setInvocationMap();
        List<File> dotFiles = dotReader.getDotFiles();
        StringBuilder methodDescription = new StringBuilder();
        String result = null;
        for (File dotFile : dotFiles) {
            String callFlow = DotProcessor.processCallGraph(dotReader.readDotFile(dotFile));
            int callFlowTokens = Tokenizer.countTokens(callFlow, ENCODING_TYPE);
            int maxMethodDescriptionTokens = MAX_DYNAMIC_TOKENS - callFlowTokens;
//            logger.info(dotFile.getName());
            List<String> callesList = invocationMap.get(dotFile.getName().replace(".dot", ""));
            int size = callesList.size();
            for (int i = 0; i < size; i++) {
                String methodSignature = callesList.get(i);
                String methodBody = ElasticsearchUtils.fetchData(methodSignature);
                methodDescription.append(gptModel.sendRequest(Prompt.METHOD_DESCRIPTION_SYSTEM_PROMPT.getContent(), String.format(Prompt.METHOD_DESCRIPTION_USER_PROMPT.getContent(), methodSignature, methodBody)));
//                logger.info(methodSignature);
                if (Tokenizer.countTokens(methodDescription.toString(), ENCODING_TYPE) > maxMethodDescriptionTokens){
                    logger.info(Tokenizer.countTokens(methodDescription.toString(), ENCODING_TYPE));
                    logger.info("MethodDescriptionTokens is too long: ");
                }
            }
//            logger.info(methodDescription);
            String systemPrompt = Prompt.BACKGROUND_SYSTEM_PROMPT.getContent();
            String userPrompt = String.format(Prompt.USER_PROMPT.getContent(), callFlow, methodDescription);
            int inputTokens = Tokenizer.countTokens(systemPrompt + userPrompt, ENCODING_TYPE);
            if (inputTokens > MAX_SEND_TOKENS){

            }
            result = gptModel.sendRequest(Prompt.BACKGROUND_SYSTEM_PROMPT.getContent(), String.format(Prompt.USER_PROMPT.getContent(), callFlow, methodDescription));
            logger.info(result);
        }
    }

    private void sendToLLM() throws Exception {

    }

}
