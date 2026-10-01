import httpx
from langchain_classic.agents import AgentExecutor, create_react_agent
from langchain_classic.tools import Tool
from langchain_core.prompts import PromptTemplate
from langchain_google_genai import ChatGoogleGenerativeAI

from core.config import settings
from services.rag.advanced_rag import retrieve_and_rerank
from services.rag.naive_rag import RAGResult, _extract_text


AGENT_SYSTEM_PROMPT = """You are FinMind, an AI personal finance assistant. You have access to the user's financial documents and transaction data. Use the available tools to find relevant information before answering. Always ground your answers in actual data.

You have access to the following tools:

{tools}

Use the following format:

Question: the input question you must answer
Thought: you should always think about what to do
Action: the action to take, should be one of [{tool_names}]
Action Input: the input to the action
Observation: the result of the action
... (this Thought/Action/Action Input/Observation can repeat N times)
Thought: I now know the final answer
Final Answer: the final answer to the original input question

Begin!

Question: {input}
Thought:{agent_scratchpad}"""


def _make_document_search_tool(user_id: str) -> Tool:
    async def search_documents(query: str) -> str:
        chunks = await retrieve_and_rerank(user_id, query)
        if not chunks:
            return "No relevant documents found."
        return "\n\n---\n\n".join(chunks[:5])

    return Tool(
        name="search_documents",
        description=(
            "Search the user's uploaded financial documents (bank statements, etc.) "
            "using semantic search. Input should be a search query describing what "
            "financial information you're looking for."
        ),
        func=lambda q: "",
        coroutine=search_documents,
    )


def _make_get_transactions_tool(user_id: str) -> Tool:
    async def get_transactions(date_range: str) -> str:
        parts = [p.strip() for p in date_range.split(",")]
        params = {}
        if len(parts) >= 1 and parts[0]:
            params["startDate"] = parts[0]
        if len(parts) >= 2 and parts[1]:
            params["endDate"] = parts[1]

        async with httpx.AsyncClient(base_url=settings.spring_boot_url) as client:
            resp = await client.get(
                "/transactions",
                params=params,
                headers={"X-Internal-User-Id": user_id},
                timeout=10.0,
            )
            if resp.status_code == 200:
                return resp.text
            return f"Error fetching transactions: {resp.status_code}"

    return Tool(
        name="get_transactions",
        description=(
            "Fetch the user's financial transactions from the database. "
            "Input should be a date range as 'start_date,end_date' in YYYY-MM-DD format "
            "(e.g. '2024-01-01,2024-01-31'). Either date can be empty to leave it open-ended."
        ),
        func=lambda q: "",
        coroutine=get_transactions,
    )


def _make_get_transaction_summary_tool(user_id: str) -> Tool:
    async def get_transaction_summary(date_range: str) -> str:
        parts = [p.strip() for p in date_range.split(",")]
        params = {}
        if len(parts) >= 1 and parts[0]:
            params["startDate"] = parts[0]
        if len(parts) >= 2 and parts[1]:
            params["endDate"] = parts[1]

        async with httpx.AsyncClient(base_url=settings.spring_boot_url) as client:
            resp = await client.get(
                "/transactions/summary",
                params=params,
                headers={"X-Internal-User-Id": user_id},
                timeout=10.0,
            )
            if resp.status_code == 200:
                return resp.text
            return f"Error fetching summary: {resp.status_code}"

    return Tool(
        name="get_transaction_summary",
        description=(
            "Get a summary of the user's spending and income for a date range, "
            "broken down by category. Input should be 'start_date,end_date' in "
            "YYYY-MM-DD format."
        ),
        func=lambda q: "",
        coroutine=get_transaction_summary,
    )


def _make_get_budgets_tool(user_id: str) -> Tool:
    async def get_budgets(_input: str) -> str:
        async with httpx.AsyncClient(base_url=settings.spring_boot_url) as client:
            resp = await client.get(
                "/budgets",
                headers={"X-Internal-User-Id": user_id},
                timeout=10.0,
            )
            if resp.status_code == 200:
                return resp.text
            return f"Error fetching budgets: {resp.status_code}"

    return Tool(
        name="get_budgets",
        description=(
            "Fetch the user's budget limits across categories. No input needed — "
            "pass an empty string."
        ),
        func=lambda q: "",
        coroutine=get_budgets,
    )


async def run(user_id: str, message: str) -> RAGResult:
    llm = ChatGoogleGenerativeAI(
        model=settings.gemini_model,
        google_api_key=settings.gemini_api_key,
        temperature=0.2,
    )

    tools = [
        _make_document_search_tool(user_id),
        _make_get_transactions_tool(user_id),
        _make_get_transaction_summary_tool(user_id),
        _make_get_budgets_tool(user_id),
    ]

    prompt = PromptTemplate.from_template(AGENT_SYSTEM_PROMPT)
    agent = create_react_agent(llm=llm, tools=tools, prompt=prompt)
    executor = AgentExecutor(
        agent=agent,
        tools=tools,
        verbose=False,
        max_iterations=6,
        handle_parsing_errors=True,
    )

    result = await executor.ainvoke({"input": message})
    raw_output = result.get("output", "I wasn't able to find a clear answer. Please try rephrasing your question.")
    output = _extract_text(raw_output)

    sources = []
    if "intermediate_steps" in result:
        for action, observation in result["intermediate_steps"]:
            if action.tool == "search_documents" and observation:
                sources.extend(observation.split("\n\n---\n\n"))

    return RAGResult(response=output, sources=sources[:5])
