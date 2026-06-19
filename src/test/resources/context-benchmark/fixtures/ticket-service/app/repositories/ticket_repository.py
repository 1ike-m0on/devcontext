from app.models.ticket import Ticket


class TicketRepository:
    def __init__(self) -> None:
        self.storage: dict[str, Ticket] = {}

    def save(self, ticket: Ticket) -> None:
        self.storage[ticket.id] = ticket

    def find_by_id(self, ticket_id: str) -> Ticket:
        return self.storage.get(ticket_id, Ticket(ticket_id, "missing", "normal", "missing"))
